// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.benchmark.swirldslog;

import static com.swirlds.logging.benchmark.config.Constants.FORK_COUNT;
import static com.swirlds.logging.benchmark.config.Constants.MEASUREMENT_ITERATIONS;
import static com.swirlds.logging.benchmark.config.Constants.MEASUREMENT_TIME_IN_SECONDS_PER_ITERATION;
import static com.swirlds.logging.benchmark.config.Constants.PARALLEL_THREAD_COUNT;
import static com.swirlds.logging.benchmark.config.Constants.WARMUP_ITERATIONS;
import static com.swirlds.logging.benchmark.config.Constants.WARMUP_TIME_IN_SECONDS_PER_ITERATION;

import com.swirlds.logging.benchmark.config.Constants;
import com.swirlds.logging.benchmark.util.Throwables;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

@State(Scope.Benchmark)
public class SwirldsLogFineGrainBenchmark extends SwirldsLogBaseBenchmark {

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
    public void logSimpleStatement() {
        logger.log(com.swirlds.logging.api.Level.INFO, "logSimpleStatement, Hello world!");
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
    public void logOffStatement() {
        logger.log(com.swirlds.logging.api.Level.OFF, "logOffStatement, Hello world!");
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
    public void logLargeStatement() {

        String logMessage =
                """
                Lorem ipsum dolor sit amet, consectetur adipiscing elit. Vivamus aliquam dolor placerat, efficitur erat a, iaculis lectus. Vestibulum lectus diam, dapibus sed porta eget, posuere ac mauris. Suspendisse nec dolor vel purus dignissim dignissim sed sed magna. Sed eu dignissim leo, ut volutpat lacus. Donec gravida ultricies dolor. Suspendisse pharetra egestas tortor, sit amet mattis tellus elementum eget. Integer eget nisl massa. In feugiat nisl ut mi tristique vulputate. Donec bibendum purus gravida massa blandit maximus. In blandit sem a malesuada pharetra. Fusce lectus erat, vulputate et tristique ac, ultricies a ex.

                Duis non nisi rutrum metus maximus fringilla. Cras nibh leo, convallis ut dignissim eget, aliquam sit amet justo. Vivamus condimentum aliquet aliquam. Nulla facilisi. Pellentesque malesuada felis mauris, sed convallis ex convallis vel. Mauris libero nibh, faucibus eget erat at, sagittis consectetur purus. Ut ac massa maximus, vulputate justo lacinia, accumsan dolor. Class aptent taciti sociosqu ad litora torquent per conubia nostra, per inceptos himenaeos. Mauris eget condimentum dolor. Nunc lacinia, lacus quis blandit aliquet, odio ex aliquet purus, et pretium urna ligula at ipsum.

                Suspendisse sollicitudin rhoncus sem, ut pulvinar nisi porttitor et. Vestibulum vehicula arcu ex, id eleifend felis rhoncus non. Quisque a arcu ullamcorper, fermentum mi in, bibendum libero. Donec dignissim ut purus et porttitor. Suspendisse ac tellus eu arcu condimentum rhoncus. Curabitur cursus blandit vulputate. Duis imperdiet velit tortor, non mollis elit rutrum a. Praesent nibh neque, condimentum id lorem et, fringilla varius mi. Donec eget varius tortor. Vestibulum vehicula leo vel tincidunt scelerisque. Proin laoreet vitae nisi auctor varius. Sed imperdiet tortor justo. Proin gravida vehicula nisl. Suspendisse elit nunc, blandit vel semper ut, tristique quis quam. Vivamus nec bibendum est. Aenean maximus, augue non ornare ornare, dui metus gravida mi, nec lacinia massa massa eu eros.

                Donec faucibus laoreet ipsum ut viverra. Ut molestie, urna nec tincidunt pretium, mauris ipsum consequat velit, mollis aliquam ipsum lorem consequat nisi. Suspendisse eros orci, luctus non scelerisque sit amet, aliquam ac sem. Etiam pellentesque eleifend ligula. Phasellus elementum auctor dui, at venenatis nibh elementum in. Duis venenatis tempus ex sit amet commodo. Fusce ut erat sit amet enim convallis pellentesque quis sit amet nisi. Sed nec ligula bibendum, volutpat dolor sit amet, maximus magna. Nam fermentum volutpat metus vitae tempus. Maecenas tempus iaculis tristique. Aenean a lobortis nisl. In auctor id ex sit amet ultrices. Vivamus at ante nec ex ultricies sagittis. Praesent odio ante, ultricies vel ante sed, mollis laoreet lectus. Aenean sagittis justo eu sapien ullamcorper commodo.
        """;
        logger.log(com.swirlds.logging.api.Level.INFO, "logLargeStatement, " + logMessage);
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
    public void logWithPlaceholders() {
        logger.log(
                com.swirlds.logging.api.Level.INFO,
                "logWithPlaceholders, Hello {}, {}, {}, {}, {}, {}, {}, {}, {}!",
                1,
                2,
                3,
                4,
                5,
                6,
                7,
                8,
                9);
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
    public void logWithMarker() {
        logger.withMarker("marker").log(com.swirlds.logging.api.Level.INFO, "logWithMarker, Hello world!");
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
    public void logWithContext() {
        logger.withContext("user-id", Constants.USER_1)
                .log(com.swirlds.logging.api.Level.INFO, "logWithContext, Hello world!");
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
    public void logWithThrowable() {
        logger.log(com.swirlds.logging.api.Level.INFO, "logWithThrowable, Hello world!", Throwables.THROWABLE);
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
    public void logWithDeepThrowable() {
        logger.log(com.swirlds.logging.api.Level.INFO, "logWithDeepThrowable, Hello world!", Throwables.DEEP_THROWABLE);
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
    public void logWorstCase() {

        String logMessage =
                """
                Lorem ipsum dolor sit amet, {} adipiscing elit. Vivamus aliquam dolor placerat, efficitur erat a, iaculis lectus. Vestibulum lectus diam, dapibus sed porta eget, posuere ac mauris. Suspendisse nec dolor vel purus dignissim dignissim sed sed magna. Sed eu dignissim leo, ut volutpat lacus. Donec gravida ultricies dolor. Suspendisse pharetra egestas tortor, sit amet mattis tellus elementum eget. Integer eget nisl massa. In feugiat nisl ut mi tristique vulputate. Donec bibendum purus gravida massa blandit maximus. In blandit sem a malesuada pharetra. Fusce lectus erat, vulputate et tristique ac, ultricies a ex.

                Duis non nisi rutrum metus maximus fringilla. Cras nibh leo, {} ut dignissim eget, aliquam sit amet justo. Vivamus condimentum aliquet aliquam. Nulla facilisi. Pellentesque malesuada felis mauris, sed convallis ex convallis vel. Mauris libero nibh, faucibus eget erat at, sagittis consectetur purus. Ut ac massa maximus, vulputate justo lacinia, accumsan dolor. Class aptent taciti sociosqu ad litora torquent per conubia nostra, per inceptos himenaeos. Mauris eget condimentum dolor. Nunc lacinia, lacus quis blandit aliquet, odio ex aliquet purus, et pretium urna ligula at ipsum.

                Suspendisse sollicitudin rhoncus sem, ut pulvinar nisi porttitor et. Vestibulum vehicula arcu ex, id eleifend felis rhoncus non. Quisque a arcu ullamcorper, fermentum mi in, bibendum libero. Donec dignissim ut purus et porttitor. Suspendisse ac tellus eu arcu condimentum rhoncus. Curabitur cursus blandit vulputate. Duis imperdiet velit tortor, non mollis elit rutrum a. Praesent nibh neque, condimentum id lorem et, fringilla varius mi. Donec eget varius tortor. Vestibulum vehicula leo vel tincidunt scelerisque. Proin laoreet vitae nisi auctor varius. Sed imperdiet tortor justo. Proin gravida vehicula nisl. Suspendisse elit nunc, blandit vel semper ut, tristique quis quam. Vivamus nec bibendum est. Aenean maximus, augue non ornare ornare, dui metus gravida mi, nec lacinia massa massa eu eros.

                Donec faucibus laoreet ipsum ut viverra. Ut molestie, urna nec tincidunt pretium, mauris ipsum {} velit, mollis aliquam ipsum lorem consequat nisi. Suspendisse eros orci, luctus non scelerisque sit amet, {} ac sem. Etiam pellentesque eleifend ligula. Phasellus elementum auctor dui, at venenatis nibh elementum in. Duis venenatis tempus ex sit amet commodo. Fusce ut erat sit amet enim convallis pellentesque quis sit amet nisi. Sed nec ligula bibendum, volutpat dolor sit amet, maximus magna. Nam fermentum volutpat metus vitae tempus. Maecenas tempus iaculis tristique. Aenean a lobortis nisl. In auctor id ex sit amet ultrices. Vivamus at ante nec ex ultricies sagittis. Praesent odio ante, ultricies vel ante sed, mollis laoreet lectus. Aenean sagittis justo eu sapien ullamcorper {}.
        """;
        logger.log(
                com.swirlds.logging.api.Level.INFO,
                "logLargeStatement, " + logMessage,
                new Object(),
                Collections.emptyList(),
                new BigDecimal("10.1"),
                "comodo",
                Throwables.DEEP_THROWABLE);
    }
}
