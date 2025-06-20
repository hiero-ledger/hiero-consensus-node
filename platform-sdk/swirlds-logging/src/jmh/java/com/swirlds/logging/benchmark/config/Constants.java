// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.benchmark.config;

import java.util.UUID;

/**
 * Static constants needed in the benchmark
 */
public class Constants {
    public static final String CONSOLE_TYPE = "CONSOLE";
    public static final String FILE_TYPE = "FILE";
    public static final String CONSOLE_AND_FILE_TYPE = "CONSOLE_AND_FILE";
    public static final String SWIRLDS = "SWIRLDS";
    public static final String LOG4J2 = "LOG4J2";

    public static final int WARMUP_ITERATIONS = 10;

    public static final int WARMUP_TIME_IN_SECONDS_PER_ITERATION = 20;

    public static final int MEASUREMENT_ITERATIONS = 20;

    public static final int MEASUREMENT_TIME_IN_SECONDS_PER_ITERATION = 200;

    public static final int PARALLEL_THREAD_COUNT = 1;

    public static final int FORK_COUNT = 1;
    public static final String ENABLE_TIME_FORMATTING_ENV = "ENABLE_TIME_FORMATTING";
    public static final String DELETE_OUTPUT_FOLDER_ENV = "DELETE_OUTPUT_FOLDER";
    public static final boolean ENABLE_TIME_FORMATTING = true;
    public static final boolean DELETE_OUTPUT_FOLDER = true;
    public static final String USER_1 = UUID.randomUUID().toString();
    public static final String USER_2 = UUID.randomUUID().toString();
    public static final String USER_3 = UUID.randomUUID().toString();
    public static final String MODE_NOT_ROLLING = "NOT_ROLLING";
    public static final String MODE_ROLLING = "ROLLING";

    private Constants() {}
}
