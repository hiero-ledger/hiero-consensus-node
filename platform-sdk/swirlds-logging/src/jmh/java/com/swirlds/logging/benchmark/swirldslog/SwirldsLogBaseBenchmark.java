// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.benchmark.swirldslog;

import static com.swirlds.logging.benchmark.config.Constants.CONSOLE_AND_FILE_TYPE;
import static com.swirlds.logging.benchmark.config.Constants.CONSOLE_TYPE;
import static com.swirlds.logging.benchmark.config.Constants.FILE_TYPE;
import static com.swirlds.logging.benchmark.config.Constants.MODE_NOT_ROLLING;
import static com.swirlds.logging.benchmark.config.Constants.MODE_ROLLING;

import com.swirlds.logging.api.Logger;
import com.swirlds.logging.api.internal.LoggingSystem;
import com.swirlds.logging.benchmark.config.Constants;
import com.swirlds.logging.benchmark.config.LoggingBenchmarkConfig;
import com.swirlds.logging.benchmark.swirldslog.plain.SwirldsLogConfig;
import com.swirlds.logging.benchmark.swirldslog.rolling.RollingSwirldsLogConfig;
import com.swirlds.logging.benchmark.util.LogFiles;
import java.util.Objects;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

@State(Scope.Benchmark)
public class SwirldsLogBaseBenchmark {

    @Param({CONSOLE_TYPE, FILE_TYPE, CONSOLE_AND_FILE_TYPE})
    public String loggingType;

    @Param({MODE_NOT_ROLLING, MODE_ROLLING})
    public String mode;

    private static final String LOGGER_NAME = Constants.SWIRLDS + "Benchmark";
    protected Logger logger;
    protected LoggingSystem loggingSystem;

    private LoggingBenchmarkConfig<LoggingSystem> config;

    @Setup(Level.Trial)
    public void init() {
        config = Objects.equals(mode, MODE_NOT_ROLLING) ? new SwirldsLogConfig() : new RollingSwirldsLogConfig();

        if (Objects.equals(loggingType, FILE_TYPE)) {
            loggingSystem = config.configureFileLogging(LogFiles.provideLogFilePath(Constants.LOG4J2, FILE_TYPE, mode));
        } else if (Objects.equals(loggingType, CONSOLE_TYPE)) {
            loggingSystem = config.configureConsoleLogging();
        } else if (Objects.equals(loggingType, CONSOLE_AND_FILE_TYPE)) {
            loggingSystem = config.configureFileAndConsoleLogging(
                    LogFiles.provideLogFilePath(Constants.LOG4J2, CONSOLE_AND_FILE_TYPE, mode));
        }
        logger = loggingSystem.getLogger(LOGGER_NAME);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        loggingSystem.stopAndFinalize();
        config.tearDown();
    }
}
