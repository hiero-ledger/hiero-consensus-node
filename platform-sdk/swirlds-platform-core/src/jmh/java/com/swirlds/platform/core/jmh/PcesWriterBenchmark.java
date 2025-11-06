// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.core.jmh;

import com.swirlds.common.io.utility.FileUtils;
import com.swirlds.common.test.fixtures.Randotron;
import com.swirlds.platform.event.preconsensus.PcesFile;
import com.swirlds.platform.event.preconsensus.PcesFileWriterType;
import com.swirlds.platform.event.preconsensus.PcesMutableFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.test.fixtures.event.TestingEventBuilder;
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
import org.openjdk.jmh.annotations.Warmup;

@State(Scope.Benchmark)
@Fork(value = 1)
@Warmup(iterations = 1, time = 1)
@Measurement(iterations = 3, time = 10)
public class PcesWriterBenchmark {

    @Param({"OUTPUT_STREAM", "FILE_CHANNEL", "FILE_CHANNEL_SYNC"})
    public PcesFileWriterType pcesFileWriterType;

    private PlatformEvent event;
    private Path directory;
    private PcesMutableFile mutableFile;

    @Setup(Level.Iteration)
    public void setup() throws IOException {
        final Randotron r = Randotron.create(0);

        event = new TestingEventBuilder(r)
                .setAppTransactionCount(3)
                .setSystemTransactionCount(1)
                .setSelfParent(new TestingEventBuilder(r).build())
                .setOtherParent(new TestingEventBuilder(r).build())
                .build();
        directory = Files.createTempDirectory("PcesWriterBenchmark");
        final PcesFile file = PcesFile.of(r.nextInstant(), 1, 0, 100, 0, directory);

        mutableFile = file.getMutableFile(pcesFileWriterType);
    }

    @TearDown(Level.Iteration)
    public void cleanup() throws IOException {
        mutableFile.close();
        FileUtils.deleteDirectory(directory);
    }

    // Linux Benchmark                              (pcesFileWriterType)   Mode  Cnt       Score        Error  Units
    // PcesWriterBenchmark.writeEvent                OUTPUT_STREAM  thrpt    3  402513.131 ± 266641.090  ops/s
    // PcesWriterBenchmark.writeEvent                 FILE_CHANNEL  thrpt    3  465751.805 ± 633311.931  ops/s
    // PcesWriterBenchmark.writeEvent            FILE_CHANNEL_SYNC  thrpt    3    1200.434 ±   2370.299  ops/s
    // PcesWriterBenchmark.writeEventAndSync         OUTPUT_STREAM  thrpt    3    1126.327 ±   2161.654  ops/s
    // PcesWriterBenchmark.writeEventAndSync          FILE_CHANNEL  thrpt    3     984.342 ±    352.266  ops/s
    // PcesWriterBenchmark.writeEventAndSync     FILE_CHANNEL_SYNC  thrpt    3     881.496 ±   2015.275  ops/s

    // Mac Benchmark                              (pcesFileWriterType)   Mode  Cnt        Score        Error  Units
    // PcesWriterBenchmark.writeEvent                OUTPUT_STREAM  thrpt    3  1303269.327 ± 504357.504  ops/s
    // PcesWriterBenchmark.writeEvent                 FILE_CHANNEL  thrpt    3   504392.068 ± 256097.035  ops/s
    // PcesWriterBenchmark.writeEvent            FILE_CHANNEL_SYNC  thrpt    3    21016.552 ±  38940.830  ops/s
    // PcesWriterBenchmark.writeEventAndSync         OUTPUT_STREAM  thrpt    3    39814.333 ± 138823.994  ops/s
    // PcesWriterBenchmark.writeEventAndSync          FILE_CHANNEL  thrpt    3      187.900 ±    129.852  ops/s
    // PcesWriterBenchmark.writeEventAndSync     FILE_CHANNEL_SYNC  thrpt    3      177.976 ±    241.287  ops/s

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    public void writeEvent() throws IOException {
        mutableFile.writeEvent(event);
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    public void writeEventAndSync() throws IOException {
        mutableFile.writeEvent(event);
        mutableFile.sync();
    }
}
