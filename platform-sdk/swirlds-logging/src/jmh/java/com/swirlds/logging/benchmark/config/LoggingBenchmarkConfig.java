// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.benchmark.config;

import com.swirlds.logging.benchmark.util.ConfigManagement;
import com.swirlds.logging.benchmark.util.LogFiles;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * An abstraction for logging benchmark configuration
 *
 * @param <T> the return type
 */
public interface LoggingBenchmarkConfig<T> {

    /**
     * Create an appender for File
     * @param logFile
     */
    @NonNull
    T configureFileLogging(final String logFile);

    /**
     * Create an appender for Console
     */
    @NonNull
    T configureConsoleLogging();

    /**
     * Create an appender for Console + File
     * @param logFile
     */
    @NonNull
    T configureFileAndConsoleLogging(final String logFile);

    /**
     * Performs the necessary operations to clean after the benchmark is done
     */
    default void tearDown() {
        if (ConfigManagement.deleteOutputFolder()) {
            LogFiles.tryDeleteDirAndContent();
        }
    }
}
