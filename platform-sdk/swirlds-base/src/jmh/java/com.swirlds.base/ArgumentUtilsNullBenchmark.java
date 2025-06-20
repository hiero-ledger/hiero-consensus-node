// SPDX-License-Identifier: Apache-2.0
package com.swirlds.base;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

public class ArgumentUtilsNullBenchmark {

    @Benchmark
    @Fork(1)
    @Threads(1)
    @BenchmarkMode(Mode.Throughput)
    @Warmup(iterations = 1, time = 1)
    @Measurement(iterations = 1, time = 1)
    public void throwArgBlankNullValues(Blackhole blackhole) {
        try {
            String arg = ArgumentUtils.throwArgBlank(null, "argument");
            blackhole.consume(arg);
        } catch (Exception e) {
            blackhole.consume(e);
        }
        try {
            String arg = ArgumentUtils.throwArgBlank("argument", null);
            blackhole.consume(arg);
        } catch (Exception e) {
            blackhole.consume(e);
        }
        try {
            String arg = ArgumentUtils.throwArgBlank(null, null);
            blackhole.consume(arg);
        } catch (Exception e) {
            blackhole.consume(e);
        }
    }
}
