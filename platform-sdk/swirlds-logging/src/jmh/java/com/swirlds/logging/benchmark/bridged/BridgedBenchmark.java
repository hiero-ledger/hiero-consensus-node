// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.benchmark.bridged;

import static com.swirlds.logging.benchmark.config.Constants.CONSOLE_AND_FILE_TYPE;
import static com.swirlds.logging.benchmark.config.Constants.CONSOLE_TYPE;
import static com.swirlds.logging.benchmark.config.Constants.FILE_TYPE;
import static com.swirlds.logging.benchmark.config.Constants.FORK_COUNT;
import static com.swirlds.logging.benchmark.config.Constants.MEASUREMENT_ITERATIONS;
import static com.swirlds.logging.benchmark.config.Constants.MEASUREMENT_TIME_IN_SECONDS_PER_ITERATION;
import static com.swirlds.logging.benchmark.config.Constants.PARALLEL_THREAD_COUNT;
import static com.swirlds.logging.benchmark.config.Constants.WARMUP_ITERATIONS;
import static com.swirlds.logging.benchmark.config.Constants.WARMUP_TIME_IN_SECONDS_PER_ITERATION;

import com.swirlds.logging.api.internal.LoggingSystem;
import com.swirlds.logging.benchmark.config.Constants;
import com.swirlds.logging.benchmark.config.LoggingBenchmarkConfig;
import com.swirlds.logging.benchmark.log4j2.Log4JRunner;
import com.swirlds.logging.benchmark.swirldslog.plain.SwirldsLogConfig;
import com.swirlds.logging.benchmark.util.LogFiles;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

@State(Scope.Benchmark)
public class BridgedBenchmark {
    private static final String LOGGER_NAME = Constants.LOG4J2 + "Benchmark";

    @Param({CONSOLE_TYPE, FILE_TYPE, CONSOLE_AND_FILE_TYPE})
    public String loggingType;

    private Logger logger;
    private Log4JRunner logRunner;

    private BridgedLog4JConfiguration config;
    private LoggingBenchmarkConfig<LoggingSystem> swirldsConfig;

    @Setup(Level.Trial)
    public void init() {
        config = new BridgedLog4JConfiguration();

        if (Objects.equals(loggingType, CONSOLE_TYPE)) {
            swirldsConfig = new SwirldsLogConfig();
            swirldsConfig.configureConsoleLogging();
            logger = config.configureBridgedLogging().getLogger(LOGGER_NAME);
        } else if (Objects.equals(loggingType, FILE_TYPE)) {
            swirldsConfig = new SwirldsLogConfig();
            swirldsConfig.configureFileLogging(LogFiles.provideLogFilePath(Constants.LOG4J2, FILE_TYPE, ""));
            logger = config.configureBridgedLogging().getLogger(LOGGER_NAME);
        } else if (Objects.equals(loggingType, CONSOLE_AND_FILE_TYPE)) {
            swirldsConfig = new SwirldsLogConfig();
            swirldsConfig.configureFileAndConsoleLogging(
                    LogFiles.provideLogFilePath(Constants.LOG4J2, CONSOLE_AND_FILE_TYPE, ""));
            logger = config.configureBridgedLogging().getLogger(LOGGER_NAME);
        }
        logRunner = new Log4JRunner(logger);
    }

    @Benchmark
    @Fork(value = FORK_COUNT)
    @Threads(PARALLEL_THREAD_COUNT)
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    @Warmup(
            iterations = WARMUP_ITERATIONS,
            time = WARMUP_TIME_IN_SECONDS_PER_ITERATION,
            timeUnit = TimeUnit.MILLISECONDS)
    @Measurement(
            iterations = MEASUREMENT_ITERATIONS,
            time = MEASUREMENT_TIME_IN_SECONDS_PER_ITERATION,
            timeUnit = TimeUnit.MILLISECONDS)
    public void log4J() {
        logRunner.run();
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        LogManager.shutdown();
        config.tearDown();
        swirldsConfig.tearDown();
    }
}
