// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.benchmark.log4j2;

import static com.swirlds.logging.benchmark.config.Constants.CONSOLE_AND_FILE_TYPE;
import static com.swirlds.logging.benchmark.config.Constants.CONSOLE_TYPE;
import static com.swirlds.logging.benchmark.config.Constants.FILE_TYPE;
import static com.swirlds.logging.benchmark.config.Constants.MODE_NOT_ROLLING;
import static com.swirlds.logging.benchmark.config.Constants.MODE_ROLLING;

import com.swirlds.logging.benchmark.config.Constants;
import com.swirlds.logging.benchmark.config.LoggingBenchmarkConfig;
import com.swirlds.logging.benchmark.log4j2.plain.Log4JConfig;
import com.swirlds.logging.benchmark.log4j2.rolling.Log4JRollingConfig;
import com.swirlds.logging.benchmark.util.LogFiles;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.spi.LoggerContext;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

@State(Scope.Benchmark)
public class Log4J2BaseBenchmark {
    private static final String LOGGER_NAME = Constants.LOG4J2 + "Benchmark";

    @Param({CONSOLE_TYPE, FILE_TYPE, CONSOLE_AND_FILE_TYPE})
    public String loggingType;

    @Param({MODE_NOT_ROLLING, MODE_ROLLING})
    public String mode;

    protected Logger logger;
    protected Log4JRunner logRunner;

    private LoggingBenchmarkConfig<LoggerContext> config;

    @Setup(Level.Trial)
    public void init() {
        config = Objects.equals(mode, MODE_NOT_ROLLING) ? new Log4JConfig() : new Log4JRollingConfig();
        if (Objects.equals(loggingType, FILE_TYPE)) {
            logger = config.configureFileLogging(LogFiles.provideLogFilePath(Constants.LOG4J2, FILE_TYPE, mode))
                    .getLogger(LOGGER_NAME);
        } else if (Objects.equals(loggingType, CONSOLE_TYPE)) {
            logger = config.configureConsoleLogging().getLogger(LOGGER_NAME);
        } else if (Objects.equals(loggingType, CONSOLE_AND_FILE_TYPE)) {
            logger = config.configureFileAndConsoleLogging(
                            LogFiles.provideLogFilePath(Constants.LOG4J2, CONSOLE_AND_FILE_TYPE, mode))
                    .getLogger(LOGGER_NAME);
        }
        logRunner = new Log4JRunner(logger);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        LogManager.shutdown();
        config.tearDown();
    }
}
