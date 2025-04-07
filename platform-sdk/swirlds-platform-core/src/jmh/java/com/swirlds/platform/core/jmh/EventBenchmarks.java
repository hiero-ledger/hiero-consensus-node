// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.core.jmh;

import com.hedera.hapi.platform.event.GossipEvent;
import com.swirlds.common.constructable.ConstructableRegistryException;
import com.swirlds.common.io.streams.MerkleDataInputStream;
import com.swirlds.common.io.streams.MerkleDataOutputStream;
import com.swirlds.platform.event.hashing.EventHasher;
import com.swirlds.platform.event.hashing.PbjStreamHasher;
import com.swirlds.platform.test.fixtures.event.TestingEventBuilder;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.hiero.consensus.model.event.PlatformEvent;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

@State(Scope.Benchmark)
@Fork(value = 1)
@Warmup(iterations = 1, time = 1)
@Measurement(iterations = 3, time = 10)
public class EventBenchmarks {

    @Param({"0"})
    public long seed;

    @Param({"20"})
    public int numApp;

    @Param({"10"})
    public int numSys;

    @Param({"PBJ_STREAM_DIGEST"})
    public HasherType hasherType;

    private PlatformEvent event;
    private MerkleDataOutputStream outStream;
    private MerkleDataInputStream inStream;
    private EventHasher eventHasher;

    @Setup
    public void setup() throws IOException, ConstructableRegistryException {
        final Random random = new Random(seed);

        event = new TestingEventBuilder(random)
                .setAppTransactionCount(numApp)
                .setSystemTransactionCount(numSys)
                .setSelfParent(new TestingEventBuilder(random).build())
                .setOtherParent(new TestingEventBuilder(random).build())
                .build();
        final PipedInputStream inputStream = new PipedInputStream();
        final PipedOutputStream outputStream = new PipedOutputStream(inputStream);
        outStream = new MerkleDataOutputStream(outputStream);
        inStream = new MerkleDataInputStream(inputStream);
        eventHasher = hasherType.newHasher();
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public void serializeDeserialize(final Blackhole bh) throws IOException {
        // results on Lazar's M1 Max MacBook Pro:
        //
        // Benchmark                                (seed)   Mode  Cnt    Score    Error   Units
        // EventSerialization.serializeDeserialize       0  thrpt    3  962.486 ± 29.252  ops/ms
        outStream.writePbjRecord(event.getGossipEvent(), GossipEvent.PROTOBUF);
        bh.consume(inStream.readPbjRecord(GossipEvent.PROTOBUF));
    }

    /*
    Results on M1 Max MacBook Pro:

    Benchmark                     (hasherType)  (numApp)  (numSys)  (seed)   Mode  Cnt       Score       Error  Units
    EventBenchmarks.hashing             LEGACY        20        10       0  thrpt    3  200225.404 ±  6112.327  ops/s
    EventBenchmarks.hashing   PBJ_BYTES_DIGEST        20        10       0  thrpt    3   85932.837 ± 16372.289  ops/s
    EventBenchmarks.hashing  PBJ_STREAM_DIGEST        20        10       0  thrpt    3  109295.516 ±  4892.847  ops/s
     */
    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    public void hashing(final Blackhole bh) {
        bh.consume(eventHasher.hashEvent(event));
    }

    public enum HasherType {
        PBJ_STREAM_DIGEST;

        public EventHasher newHasher() {
            return switch (this) {
                case PBJ_STREAM_DIGEST -> new PbjStreamHasher();
            };
        }
    }
}
