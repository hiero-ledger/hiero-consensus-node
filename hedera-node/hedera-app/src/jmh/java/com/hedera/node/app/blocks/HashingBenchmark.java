// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks;

import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_ACCOUNTS;
import static com.hedera.node.app.hapi.utils.CommonUtils.sha384DigestOrThrow;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.output.MapChangeKey;
import com.hedera.hapi.block.stream.output.MapChangeValue;
import com.hedera.hapi.block.stream.output.MapUpdateChange;
import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.block.stream.output.StateChanges;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.node.app.blocks.impl.ConcurrentStreamingTreeHasher;
import com.hedera.node.app.blocks.impl.NaiveStreamingTreeHasher;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
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
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

@State(Scope.Benchmark)
@Fork(value = 1)
@Warmup(iterations = 1)
@Measurement(iterations = 3)
public class HashingBenchmark {
    private static final int MAX_STATE_CHANGES = 128;
    private static final SplittableRandom RANDOM = new SplittableRandom(1_234_567L);

    public static void main(String... args) throws Exception {
        org.openjdk.jmh.Main.main(new String[] {"com.hedera.node.app.blocks.HashingBenchmark.hashItemTree"});
    }

    @Param({"10000"})
    private int numLeafHashes;

    private List<byte[]> leafHashes;
    private Bytes expectedAnswer;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        final var digest = sha384DigestOrThrow();
        leafHashes = new ArrayList<>(numLeafHashes);
        for (int i = 0; i < numLeafHashes; i++) {
            final var item = randomBlockItem();
            final var hash = digest.digest(BlockItem.PROTOBUF.toBytes(item).toByteArray());
            leafHashes.add(hash);
        }
        expectedAnswer = NaiveStreamingTreeHasher.computeRootHash(leafHashes);
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    public void hashItemTree(@NonNull final Blackhole blackhole) {
        //                final var subject = new NaiveStreamingTreeHasher();
        final var subject = new ConcurrentStreamingTreeHasher(ForkJoinPool.commonPool());
        for (final var hash : leafHashes) {
            subject.addLeaf(ByteBuffer.wrap(hash));
        }
        final var rootHash = subject.rootHash().join();
        if (!rootHash.equals(expectedAnswer)) {
            throw new IllegalStateException("Expected " + expectedAnswer + " but got " + rootHash);
        }
        blackhole.consume(rootHash);
    }

    private static BlockItem randomBlockItem() {
        return BlockItem.newBuilder()
                .stateChanges(StateChanges.newBuilder()
                        .consensusTimestamp(randomTimestamp())
                        .stateChanges(randomStateChanges()))
                .build();
    }

    private static StateChange[] randomStateChanges() {
        final var numStateChanges = RANDOM.nextInt(MAX_STATE_CHANGES);
        final var stateChanges = new StateChange[numStateChanges];
        for (int i = 0; i < numStateChanges; i++) {
            stateChanges[i] = StateChange.newBuilder()
                    .stateId(STATE_ID_ACCOUNTS.protoOrdinal())
                    .mapUpdate(MapUpdateChange.newBuilder()
                            .key(MapChangeKey.newBuilder()
                                    .accountIdKey(AccountID.newBuilder()
                                            .accountNum(RANDOM.nextLong(Long.MAX_VALUE))
                                            .build())
                                    .build())
                            .value(MapChangeValue.newBuilder().accountValue(Account.DEFAULT))
                            .build())
                    .build();
        }
        return stateChanges;
    }

    private static Timestamp randomTimestamp() {
        return Timestamp.newBuilder()
                .seconds(RANDOM.nextLong(Instant.MAX.getEpochSecond()))
                .nanos(RANDOM.nextInt(Instant.MAX.getNano()))
                .build();
    }
}
