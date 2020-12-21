package cloud.testload.jmeter;

import main.java.cloud.testload.jmeter.config.clickhouse.ClickHouseConfig;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.visualizers.backend.AbstractBackendListenerClient;
import org.apache.jmeter.visualizers.backend.BackendListenerContext;
import org.apache.jmeter.threads.JMeterContextService.ThreadCounts;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ru.yandex.clickhouse.ClickHouseDataSource;
import ru.yandex.clickhouse.settings.ClickHouseProperties;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;


/**
 * Backend listener that writes JMeter metrics to ClickHouse directly.
 *
 * @author Alexander Babaev (minor changes and improvements)
 *
 */
public class ClickHouseBackendListenerClientV2 extends AbstractBackendListenerClient implements Runnable {
    /**
     * Logger.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(ClickHouseBackendListenerClient.class);

    /**
     * Buffer.
     */
    private static final List<SampleResult> allSampleResults = new ArrayList<SampleResult>();

    /**
     * Parameter Keys.
     */
    private static final String KEY_USE_REGEX_FOR_SAMPLER_LIST = "useRegexForSamplerList";
    private static final String KEY_PROFILE_NAME = "profileName";
    private static final String KEY_RUN_ID = "runId";
    private static final String KEY_SAMPLERS_LIST = "samplersList";
    private static final String KEY_RECORD_SUB_SAMPLES = "recordSubSamples";
    private static final String KEY_RECORD_GROUP_BY_COUNT = "groupByCount";
    private static final String KEY_RECORD_BATCH_SIZE= "batchSize";
    private static final String KEY_RECORD_LEVEL = "recordDataLevel";
    private static final String KEY_CREATE_DEF = "createDefinitions";
    private static final String KEY_GRAFANA_URL = "grafanaUrl";
    private static final String KEY_START_TEST = "startTime";
    private static final String KEY_END_TEST = "endTime";


    /**
     * Constants.
     */
    private static final String SEPARATOR = ";";
    private static final int ONE_MS_IN_NANOSECONDS = 1000000;

    /**
     * Scheduler for periodic metric aggregation.
     */
    private ScheduledExecutorService scheduler;

    /**
     * Name of the test.
     */
    private String profileName;

    /**
     * Name of the host(locahost or container  etc)
     */
    private String hostname;
    /**
     * A unique identifier for a single execution (aka 'run') of a load test.
     * In a CI/CD automated performance test, a Jenkins or Bamboo build id would be a good value for this.
     */
    private String runId;

    /**
     * List of samplers to record.
     */
    private String samplersList;
    /**
     */
    private String startTest;

    /**
     */
    private String endTest;
    /**
     */
    private String grafanaUrl;
    /**
     * Regex if samplers are defined through regular expression.
     */
    private String regexForSamplerList;

    /**
     * Set of samplers to record.
     */
    private Set<String> samplersToFilter;

    /**
     * ClickHouse configuration.
     */
    ClickHouseConfig clickhouseConfig;

    /**
     * clickHouse DS.
     */
    private ClickHouseDataSource clickHouse;
    private Connection connection;

    /**
     * Random number generator
     */
    private Random randomNumberGenerator;

    /**
     * Indicates whether to record Subsamples
     */
    private boolean recordSubSamples;

    /**
     * Indicates whether to create database/tables
     */
    private boolean createDefinitions;

    /**
     * Indicates whether to aggregate and level statistic
     */
    private int groupByCount;
    private int batchSize;
    private String recordLevel;

    /**
     * Processes sampler results.
     */
    public void handleSampleResults(List<SampleResult> sampleResults, BackendListenerContext context) {
        // Gather only regex results to array
        sampleResults.forEach(it -> {
            //write every filtered result to array
            if (checkFilter(it)) {
                switch (recordLevel){
                    case "info":
                        it.setSamplerData("");
                        it.setResponseData("");
                        break;
                    case "error":
                        if (it.getErrorCount()==0) {
                            it.setSamplerData("");
                            it.setResponseData("");
                        }
                        break;
                    default:
                        break;
                }
                allSampleResults.add(it);
            }
            if (recordSubSamples) {
                //write every filtered sub_result to array
                for (SampleResult subResult : it.getSubResults()) {
                    if (checkFilter(subResult)) {
                        switch (recordLevel){
                            case "info":
                                subResult.setSamplerData("");
                                subResult.setResponseData("");
                                break;
                            case "error":
                                if (it.getErrorCount()==0) {
                                    subResult.setSamplerData("");
                                    subResult.setResponseData("");
                                }
                                break;
                            default:
                                break;
                        }
                        allSampleResults.add(subResult);
                    } else {
                        if ((recordLevel.equals("error")&&(it.getErrorCount()>0)&&(subResult.getErrorCount()>0))){
                            allSampleResults.add(subResult);
                        }
                    }
                }
            }
        });

        //Flush point(s) every group by Count
        if (recordLevel.equals("aggregate")) {
            if (allSampleResults.size() >= groupByCount*batchSize) {
                flushAggregatedBatchPoints();
            }
        } else {
            if (allSampleResults.size() >= batchSize) {
                flushBatchPoints();
            }
        }
    };

    private boolean checkFilter(SampleResult sample){
        return 	((null != regexForSamplerList && sample.getSampleLabel().matches(regexForSamplerList)) || samplersToFilter.contains(sample.getSampleLabel()));
    }

    //Save one-item-array to DB
    private void flushBatchPoints() {
        try {
            PreparedStatement point = connection.prepareStatement("INSERT INTO jmresults (timestamp_sec, timestamp_millis, profile_name, run_id, hostname, thread_name, sample_label, sampler_type, startedThreads, errors_count, average_time, request, response, res_code)" +
                    " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)");
            allSampleResults.forEach(it -> {
                try {
                    int tc = JMeterContextService.getThreadCounts().activeThreads;
                    String sampler_type = "";
                    if (it instanceof HTTPSampleResult){
                        sampler_type = "REQUEST";
                    } else {
                        sampler_type = "TRANSACTION";
                    }
                    point.setTimestamp(1,new Timestamp(it.getTimeStamp()));
                    point.setLong(2, it.getTimeStamp());
                    point.setString(3, profileName);
                    point.setString(4, runId);
                    point.setString(5, hostname);
                    point.setString(6, it.getThreadName());
                    point.setString(7, it.getSampleLabel());
                    point.setString(8, sampler_type);
                    point.setInt(9, tc);
                    point.setInt(10, it.getErrorCount());
                    point.setDouble(11, it.getTime());
                    switch (recordLevel){
                        case "debug":
                            point.setString(12, it.getSamplerData());
                            point.setString(13, it.getResponseDataAsString());
                            break;
                        case "error":
                            if (it.getErrorCount()>0) {
                                point.setString(12, it.getSamplerData());
                                point.setString(13, it.getResponseDataAsString());
                            }
                            else {
                                point.setString(12, "");
                                point.setString(13, "");
                            }
                            break;
                        default:
                            point.setString(12, "");
                            point.setString(13, "");
                            break;
                    }
                    point.setString(14,it.getResponseCode());
                    point.addBatch();
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            });

            point.executeBatch();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        allSampleResults.clear();
    }
    //Aggregate and Save array to DB
    private void flushAggregatedBatchPoints()
    {
        //group aggregation
        final Map<String,JMPoint> samplesTst = allSampleResults.stream().collect(Collectors.groupingBy(SampleResult::getSampleLabel,
                Collectors.collectingAndThen(Collectors.toList(), list -> {
                            long errorsCount = list.stream().collect(Collectors.summingLong(SampleResult::getErrorCount));
                            long count = list.stream().collect(Collectors.counting());
                            double average = list.stream().collect(Collectors.averagingDouble(SampleResult::getTime));
                            return new JMPoint("aggregate",errorsCount,count, average);
                        }
                )));
        //save aggregates to DB
        try {
            PreparedStatement point = connection.prepareStatement("INSERT INTO jmresults (timestamp_sec, timestamp_millis, profile_name, run_id, hostname, thread_name, sample_label, sampler_type, startedThreads, errors_count, average_time, request, response)" +
                    " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)");
            samplesTst.forEach((pointName,pointData)-> {
                try {
                    int tc = JMeterContextService.getThreadCounts().activeThreads;
                    point.setTimestamp(1,new Timestamp(System.currentTimeMillis()));
                    point.setLong(2,  System.currentTimeMillis());
                    point.setString(3, profileName);
                    point.setString(4, runId);
                    point.setString(5, hostname);
                    point.setString(6, pointData.getThreadName());
                    point.setString(7, pointName);
                    point.setString(8, "");
                    point.setLong(9, tc);
                    point.setLong(10, pointData.getErrorCount());
                    point.setDouble(11, pointData.getAverageTimeInt());
                    point.setString(12, "");
                    point.setString(13, "");
                    point.setString(14, "");
                    point.addBatch();
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            });
            point.executeBatch();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        allSampleResults.clear();
    }

    /**
     * Abstract imp
     */
    public void run() {
    }

    @Override
    public Arguments getDefaultParameters() {
        Arguments arguments = new Arguments();
        arguments.addArgument(KEY_PROFILE_NAME, "TEST");
        arguments.addArgument(KEY_RUN_ID, "R001");
        arguments.addArgument(ClickHouseConfig.KEY_CLICKHOUSE_URL, ClickHouseConfig.DEFAULT_CLICKHOUSE_URL);
        arguments.addArgument(ClickHouseConfig.KEY_CLICKHOUSE_USER, "default");
        arguments.addArgument(ClickHouseConfig.KEY_CLICKHOUSE_PASSWORD, "");
        arguments.addArgument(ClickHouseConfig.KEY_CLICKHOUSE_DATABASE, ClickHouseConfig.DEFAULT_DATABASE);
        arguments.addArgument(KEY_SAMPLERS_LIST, ".*");
        arguments.addArgument(KEY_USE_REGEX_FOR_SAMPLER_LIST, "true");
        arguments.addArgument(KEY_RECORD_SUB_SAMPLES, "true");
        arguments.addArgument(KEY_RECORD_GROUP_BY_COUNT, "100");
        arguments.addArgument(KEY_RECORD_BATCH_SIZE, "1000");
        arguments.addArgument(KEY_RECORD_LEVEL, "info");
        arguments.addArgument(KEY_CREATE_DEF, "true");
        arguments.addArgument(KEY_GRAFANA_URL,"d/jmeterhouseeng/jmeter-results-from-clickhouse");
        arguments.addArgument(KEY_START_TEST,"");
        arguments.addArgument(KEY_END_TEST,"");
        return arguments;
    }

    @Override
    public void setupTest(BackendListenerContext context) throws Exception {
        profileName = context.getParameter(KEY_PROFILE_NAME, "TEST");
        runId = context.getParameter(KEY_RUN_ID,"R001");
        groupByCount = context.getIntParameter(KEY_RECORD_GROUP_BY_COUNT, 100);
        batchSize = context.getIntParameter(KEY_RECORD_BATCH_SIZE, 1000);
        recordLevel=context.getParameter(KEY_RECORD_LEVEL,"info");
        createDefinitions=Boolean.parseBoolean(context.getParameter(KEY_CREATE_DEF,"true"));
        hostname=getHostname();
        if (context.getParameter(KEY_START_TEST).equals("")){
            startTest = String.valueOf(System.currentTimeMillis());
        } else {
            startTest = context.getParameter(KEY_START_TEST);
        }
        setupClickHouseClientWithoutDatabase(context);
        createDatabaseIfNotExistent();
        setupClickHouseClient(context);
        parseSamplers(context);
        scheduler = Executors.newScheduledThreadPool(1);
        scheduler.scheduleAtFixedRate(this, 1, 1, TimeUnit.SECONDS);
        // Indicates whether to write sub sample records to the database
        recordSubSamples = Boolean.parseBoolean(context.getParameter(KEY_RECORD_SUB_SAMPLES, "false"));
    }

    @Override
    public void teardownTest(BackendListenerContext context) throws Exception {
        LOGGER.info("Shutting down clickHouse scheduler...");
        if (context.getParameter(KEY_END_TEST).equals("")){
            endTest = String.valueOf(System.currentTimeMillis());
        } else {
            endTest = context.getParameter(KEY_END_TEST);
        }
        grafanaUrl = "<a href=\"" +context.getParameter(KEY_GRAFANA_URL, "d/jmeterhouseeng/jmeter-results-from-clickhouse") +
                "?from="+(Long.parseLong(startTest)-10000)+"&to="+(Long.parseLong(endTest)+10000)+"\">Dashboard</a>";
        PreparedStatement point = connection.prepareStatement("INSERT INTO jmresults_tests (timestamp_sec, test_started, test_ended, test_name, run_id, max_users, duration, grafana_url)" +
                " VALUES (?,?,?,?,?,?,?,?)");

        try {
            point.setTimestamp(1, new Timestamp(Long.parseLong(startTest)));
            point.setLong(2, Long.parseLong(startTest));
            point.setLong(3, Long.parseLong(endTest));
            point.setString(4, profileName);
            point.setString(5, runId);
            point.setLong(6, JMeterContextService.getThreadCounts().startedThreads);
            point.setLong(7, (Long.parseLong(endTest) - Long.parseLong(startTest)) / 1000);
            point.setString(8, grafanaUrl);
            point.addBatch();
            point.executeBatch();
        }catch (SQLException e) {
            e.printStackTrace();
        }
        if (recordLevel.equals("aggregate")) {
            flushAggregatedBatchPoints();
        }else flushBatchPoints();
        super.teardownTest(context);
    }



    /**
     * Setup clickHouse client.
     *
     * @param context
     *            {@link BackendListenerContext}.
     */
    private void setupClickHouseClient(BackendListenerContext context) {
        clickhouseConfig= new ClickHouseConfig(context);
        ClickHouseProperties properties = new ClickHouseProperties();
        properties.setCompress(true);
        properties.setDatabase(clickhouseConfig.getClickhouseDatabase());
        properties.setUser(clickhouseConfig.getClickhouseUser());
        properties.setPassword(clickhouseConfig.getClickhousePassword());
        properties.setConnectionTimeout(60000); //hardcode to 60 sec
        properties.setSocketTimeout(60000); //hardcode to 60 sec
        clickHouse = new ClickHouseDataSource("jdbc:clickhouse://"+clickhouseConfig.getClickhouseURL(), properties);
        try {
            connection = clickHouse.getConnection();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void setupClickHouseClientWithoutDatabase(BackendListenerContext context) {
        clickhouseConfig= new ClickHouseConfig(context);
        ClickHouseProperties properties = new ClickHouseProperties();
        properties.setCompress(true);
        properties.setUser(clickhouseConfig.getClickhouseUser());
        properties.setPassword(clickhouseConfig.getClickhousePassword());
        clickHouse = new ClickHouseDataSource("jdbc:clickhouse://"+clickhouseConfig.getClickhouseURL(), properties);
        try {
            connection = clickHouse.getConnection();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    /**
     * Creates the configured database in clickhouse if it does not exist yet.
     */
    private void createDatabaseIfNotExistent() {

        String dbtemplate_database="create database IF NOT EXISTS " + clickhouseConfig.getClickhouseDatabase();

        String dbtemplate_data="create table IF NOT EXISTS " +
                clickhouseConfig.getClickhouseDatabase()+".jmresults_data\n" +
                "(\n" +
                "\ttimestamp_sec DateTime,\n" +
                "\ttimestamp_millis UInt64,\n" +
                "\tprofile_name LowCardinality(String),\n" +
                "\trun_id LowCardinality(String),\n" +
                "\thostname LowCardinality(String),\n" +
                "\tthread_name LowCardinality(String),\n" +
                "\tsample_label LowCardinality(String),\n" +
                "\tsampler_type LowCardinality(String),\n" +
                "\tstartedThreads UInt64,\n" +
                "\terrors_count UInt64,\n" +
                "\taverage_time Float64,\n" +
                "\trequest String,\n" +
                "\tresponse String,\n" +
                "\tres_code LowCardinality(String)\n" +
                ")\n" +
                "engine = MergeTree\n" +
                "PARTITION BY toYYYYMMDD(timestamp_sec)\n" +
                "ORDER BY (timestamp_sec)\n" +
                "TTL timestamp_sec + INTERVAL 2 MONTH\n" +
                "SETTINGS index_granularity = 8192";

        String dbtemplate_stats="CREATE MATERIALIZED VIEW IF NOT EXISTS " +
                clickhouseConfig.getClickhouseDatabase()+".jmresults_statistic (\n" +
                "`timestamp_sec` DateTime Codec(DoubleDelta, LZ4),\n" +
                "`timestamp_millis` UInt64 Codec(DoubleDelta, LZ4),\n" +
                "`profile_name` LowCardinality(String),\n" +
                "`run_id` LowCardinality(String),\n" +
                "`thread_name` LowCardinality(String),\n" +
                "`hostname` String Codec(LZ4),\n" +
                "`sample_label` LowCardinality(String),\n" +
                "`sampler_type` LowCardinality(String),\n" +
                "`startedThreads` UInt64 Codec(Gorilla, LZ4),\n" +
                "`errors_count` UInt64 Codec(Gorilla, LZ4),\n" +
                "`average_time` Float64 Codec(Gorilla, LZ4),\n" +
                "`res_code` LowCardinality(String))\n" +
                "    ENGINE = MergeTree\n" +
                "        PARTITION BY toYYYYMM(timestamp_sec)\n" +
                "        ORDER BY (timestamp_sec,\n" +
                "                  profile_name,\n" +
                "                  run_id,\n" +
                "                  hostname,\n" +
                "                  sample_label,\n" +
                "                  sampler_type,\n" +
                "                  thread_name,\n" +
                "                  res_code)\n" +
                "        SETTINGS index_granularity = 8192\n" +
                "AS\n" +
                "SELECT timestamp_sec,\n" +
                "       timestamp_millis,\n" +
                "       profile_name,\n" +
                "       run_id,\n" +
                "       thread_name,\n" +
                "       hostname,\n" +
                "       sample_label,\n" +
                "       sampler_type,\n" +
                "       startedThreads,\n" +
                "       errors_count,\n" +
                "       average_time,\n" +
                "       res_code\n" +
                "FROM " +
                clickhouseConfig.getClickhouseDatabase()+".jmresults_data";

        String dbtemplate_buff="create table IF NOT EXISTS " +
                clickhouseConfig.getClickhouseDatabase()+".jmresults\n" +
                "(\n" +
                "\ttimestamp_sec DateTime,\n" +
                "\ttimestamp_millis UInt64,\n" +
                "\tprofile_name LowCardinality(String),\n" +
                "\trun_id LowCardinality(String),\n" +
                "\thostname LowCardinality(String),\n" +
                "\tthread_name LowCardinality(String),\n" +
                "\tsample_label LowCardinality(String),\n" +
                "\tsampler_type LowCardinality(String),\n" +
                "\tstartedThreads UInt64,\n" +
                "\terrors_count UInt64,\n" +
                "\taverage_time Float64,\n" +
                "\trequest String,\n" +
                "\tresponse String,\n" +
                "\tres_code LowCardinality(String)\n" +
                ")\n" +
                "engine = Buffer("+clickhouseConfig.getClickhouseDatabase()+", jmresults_data, 16, 10, 60, 10000, 100000, 1000000, 10000000)";

        String dbtemplate_tests="create table IF NOT EXISTS " +
                clickhouseConfig.getClickhouseDatabase()+".jmresults_tests\n" +
                "(\n" +
                "\ttimestamp_sec DateTime,\n" +
                "\ttest_started UInt64,\n" +
                "\ttest_ended UInt64,\n" +
                "\ttest_name LowCardinality(String),\n" +
                "\trun_id LowCardinality(String),\n" +
                "\tmax_users UInt64,\n" +
                "\tduration UInt64,\n" +
                "\tgrafana_url LowCardinality(String)\n" +
                ")\n" +
                "engine = MergeTree\n" +
                "PARTITION BY toYYYYMMDD(timestamp_sec)\n" +
                "ORDER BY (timestamp_sec)\n" +
                "TTL timestamp_sec + INTERVAL 2 MONTH\n" +
                "SETTINGS index_granularity = 8192";
        try {
            if (createDefinitions) {
                connection.createStatement().execute(dbtemplate_database);
                connection.createStatement().execute(dbtemplate_data);
                connection.createStatement().execute(dbtemplate_buff);
                connection.createStatement().execute(dbtemplate_stats);
                connection.createStatement().execute(dbtemplate_tests);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    /**
     * Parses list of samplers.
     *
     * @param context
     *            {@link BackendListenerContext}.
     */
    private void parseSamplers(BackendListenerContext context) {
        samplersList = context.getParameter(KEY_SAMPLERS_LIST, "");
        samplersToFilter = new HashSet<String>();
        if (context.getBooleanParameter(KEY_USE_REGEX_FOR_SAMPLER_LIST, false)) {
            regexForSamplerList = samplersList;
        } else {
            regexForSamplerList = null;
            String[] samplers = samplersList.split(SEPARATOR);
            samplersToFilter = new HashSet<String>();
            for (String samplerName : samplers) {
                samplersToFilter.add(samplerName);
            }
        }
    }

    /**
     * internal hostname function
     */
    private static String getHostname() throws UnknownHostException {
        InetAddress iAddress = InetAddress.getLocalHost();
        String hostName = iAddress.getHostName();
        return hostName;
    }
}
