// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.hiero.otter.fixtures.junit.FalconTestExtension;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Annotation to mark a method as a Falcon test.
 *
 * <p>A Falcon test runs against the Falcon environment, which trades functionality for speed so that a test can be
 * repeated many times with a different seed each run. A Falcon test method can define one parameter of type
 * {@link TestEnvironment} or {@link org.hiero.otter.fixtures.falcon.FalconTestEnvironment} to access the test
 * environment.
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@TestTemplate
@ExtendWith({FalconTestExtension.class})
@Tag("falcon")
public @interface FalconTest {

    /**
     * Specifies the number of repetitions to run the test. Ignored if {@link #randomSeed()} is set. Can be overridden
     * with the system property {@code falcon.repetitions}.
     *
     * @return the number of repetitions to run the test
     */
    int repetitions() default 1_000;

    /**
     * Specifies the number of repetitions that may fail before the rest of the sweep is skipped. Once this many
     * repetitions have failed, the remaining repetitions are not run. Ignored if {@link #randomSeed()} is set.
     *
     * <p>The default of {@code 1} halts further repetitions as soon as a single repetition fails. Values must be
     * greater than zero and less than {@link #repetitions()}, or {@link Integer#MAX_VALUE} to run the whole sweep no
     * matter how many repetitions fail. Note that the threshold is compared against the {@link #repetitions()} declared
     * here, not against a count supplied via {@code falcon.repetitions}; if that system property lowers the number of
     * repetitions below the threshold, the threshold simply never trips.
     *
     * @return the number of failed repetitions that stops the sweep
     */
    int failureThreshold() default 1;

    /**
     * Specifies the seed of a single repetition to replay. If set to a non-zero value, the test runs exactly once with
     * this seed instead of running a sweep.
     *
     * <p>If set to {@code 0} (the default), the test runs {@link #repetitions()} repetitions, each with its own
     * randomly
     * drawn seed.
     *
     * @return the seed of the repetition to replay, or {@code 0} to run a sweep
     */
    long randomSeed() default 0L;

    /**
     * Specifies the granularity of the simulation in microseconds, i.e. the amount of simulated time that passes with
     * each tick. Every timestamp in the simulation is quantized to this granularity, so it has to be well below the
     * simulated network latency for timing measurements to be meaningful.
     *
     * <p>Must be positive. The default of 10ms is suitable for tests that do not measure latencies.
     *
     * @return the granularity of the simulation in microseconds
     */
    long granularityMicros() default 10_000L;
}
