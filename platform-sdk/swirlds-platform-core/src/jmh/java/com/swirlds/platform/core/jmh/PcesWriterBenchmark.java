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
import org.hiero.consensus.model.event.AncientMode;
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
        final PcesFile file = PcesFile.of(AncientMode.GENERATION_THRESHOLD, r.nextInstant(), 1, 0, 100, 0, directory);

        mutableFile = file.getMutableFile(pcesFileWriterType);
    }

    @TearDown(Level.Iteration)
    public void cleanup() throws IOException {
        mutableFile.close();
        FileUtils.deleteDirectory(directory);
    }
    /*
    Results on a M1 Max MacBook Pro:

    Benchmark                       (syncEveryEvent)  (useFileChannelWriter)   Mode  Cnt       Score        Error  Units
    PcesWriterBenchmark.writeEvent              true                    true  thrpt    3   12440.268 ±  42680.146  ops/s
    PcesWriterBenchmark.writeEvent              true                   false  thrpt    3   16244.412 ±  38461.148  ops/s
    PcesWriterBenchmark.writeEvent             false                    true  thrpt    3  411138.079 ± 110692.138  ops/s
    PcesWriterBenchmark.writeEvent             false                   false  thrpt    3  643582.781 ± 154393.415  ops/s
    */
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
