// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.benchmark;

import com.swirlds.logging.benchmark.swirldslog.SwirldsLogBenchmark;
import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.profile.MemPoolProfiler;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * Convenience class to run benchmark with profilers from the IDE
 */
public class Runner {

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(SwirldsLogBenchmark.class.getSimpleName())
                // .include(Log4J2Benchmark.class.getSimpleName())
                .param("mode", "ROLLING")
                .param("loggingType", "FILE")
                .addProfiler(GCProfiler.class)
                .addProfiler(MemPoolProfiler.class)
                // .addProfiler(PausesProfiler.class)
                // .addProfiler(StackProfiler.class)
                // .addProfiler(ClassloaderProfiler.class)
                .build();

        new org.openjdk.jmh.runner.Runner(opt).run();
    }
}
