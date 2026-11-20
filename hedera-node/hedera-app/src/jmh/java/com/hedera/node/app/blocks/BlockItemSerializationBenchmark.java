// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.output.MapChangeKey;
import com.hedera.hapi.block.stream.output.MapChangeValue;
import com.hedera.hapi.block.stream.output.MapUpdateChange;
import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.block.stream.output.StateChanges;
import com.hedera.hapi.block.stream.output.StateIdentifier;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.AccountApprovalForAllAllowance;
import com.hedera.hapi.node.state.token.AccountCryptoAllowance;
import com.hedera.hapi.node.state.token.AccountFungibleTokenAllowance;
import com.hedera.pbj.runtime.OneOf;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
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

/**
 * Compares two ways of serializing the {@code STATE_CHANGES} block item that a {@code CryptoApproveAllowance}
 * produces, which carries the <b>entire</b> updated {@link Account}, including every allowance it holds. This is the
 * work done per item by {@code BlockStreamManagerImpl.ParallelTask}.
 * <ul>
 *   <li>{@link #toBytesThenToByteArray}: the previous implementation, {@code Codec.toBytes(item).toByteArray()}. It
 *       goes through the streaming {@code WritableSequentialData} path and then copies the result once more.</li>
 *   <li>{@link #toBytesOnly}: {@code Codec.toBytes(item)} with the result kept as PBJ {@code Bytes}. It still uses the
 *       streaming path but skips the final copy.</li>
 *   <li>{@link #measureAndWriteToArray}: the current implementation, sizing an exact array and writing into it via
 *       {@code Codec.write(item, byte[], 0)}, the codec's byte-array path.</li>
 * </ul>
 *
 * <p><b>This benchmark is for manual runs only.</b> {@code hedera-app} applies {@code org.hiero.gradle.feature.benchmark}
 * but not {@code benchmark-tracking}, so it is not part of the nightly tracked benchmarks or the CI smoke run. Run it
 * from the merged benchmark jar (a plain {@code :app:jmh} would run every benchmark in the module, which takes hours):
 * <pre>
 * ./gradlew :app:jmhJarWithMergedServiceFiles
 * java -jar hedera-node/hedera-app/build/libs/app-&lt;version&gt;-jmh-merged.jar BlockItemSerializationBenchmark -prof gc
 * </pre>
 * or run {@link #main(String[])} from the IDE. The {@code gc} profiler ({@code -prof gc}) reports allocation per
 * operation as well as time.
 *
 * <p>Caveat: the item objects are built once and reused, so PBJ's per-object cached encoded size is warm for both
 * variants. In production each item is serialized once, so its size computation is also part of the cost; that
 * affects both variants equally and so understates the absolute per-item cost but not the difference between them.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(value = 1)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
public class BlockItemSerializationBenchmark {

    public static void main(final String... args) throws Exception {
        org.openjdk.jmh.Main.main(new String[] {BlockItemSerializationBenchmark.class.getSimpleName()});
    }

    /** The number of fungible token allowances on the approving account. The default network limit is 100. */
    @Param({"1", "25", "100"})
    private int numTokenAllowances;

    private BlockItem item;

    @Setup
    public void setUp() {
        item = stateChangesItemFor(accountWithTokenAllowances(numTokenAllowances));
        // Guard: both implementations must produce identical bytes, or the comparison is meaningless
        final var expected = toBytesThenToByteArrayImpl();
        final var actual = measureAndWriteToArrayImpl();
        if (!Arrays.equals(expected, actual)) {
            throw new IllegalStateException(
                    "Serialization variants disagree for " + numTokenAllowances + " allowances");
        }
    }

    @Benchmark
    public void toBytesThenToByteArray(final Blackhole blackhole) {
        blackhole.consume(toBytesThenToByteArrayImpl());
    }

    @Benchmark
    public void toBytesOnly(final Blackhole blackhole) {
        blackhole.consume(BlockItem.PROTOBUF.toBytes(item));
    }

    @Benchmark
    public void measureAndWriteToArray(final Blackhole blackhole) {
        blackhole.consume(measureAndWriteToArrayImpl());
    }

    private byte[] toBytesThenToByteArrayImpl() {
        return BlockItem.PROTOBUF.toBytes(item).toByteArray();
    }

    private byte[] measureAndWriteToArrayImpl() {
        final var codec = BlockItem.PROTOBUF;
        final byte[] bytes = new byte[codec.measureRecord(item)];
        codec.write(item, bytes, 0);
        return bytes;
    }

    private static Account accountWithTokenAllowances(final int numTokenAllowances) {
        final var tokenAllowances = new ArrayList<AccountFungibleTokenAllowance>(numTokenAllowances);
        for (int i = 0; i < numTokenAllowances; i++) {
            tokenAllowances.add(AccountFungibleTokenAllowance.newBuilder()
                    .tokenId(TokenID.newBuilder().tokenNum(1_000L + i))
                    .spenderId(accountId(2_000L + i))
                    .amount(1_000_000L + i)
                    .build());
        }
        return Account.newBuilder()
                .accountId(accountId(1_234L))
                .tinybarBalance(123_456_789_012L)
                .memo("benchmark account")
                .cryptoAllowances(List.of(
                        new AccountCryptoAllowance(accountId(3_000L), 5_000L),
                        new AccountCryptoAllowance(accountId(3_001L), 6_000L)))
                .approveForAllNftAllowances(List.of(new AccountApprovalForAllAllowance(
                        TokenID.newBuilder().tokenNum(4_000L).build(), accountId(3_002L))))
                .tokenAllowances(tokenAllowances)
                .build();
    }

    private static BlockItem stateChangesItemFor(final Account account) {
        final var change = MapUpdateChange.newBuilder()
                .key(new MapChangeKey(new OneOf<>(MapChangeKey.KeyChoiceOneOfType.ACCOUNT_ID_KEY, account.accountId())))
                .value(new MapChangeValue(new OneOf<>(MapChangeValue.ValueChoiceOneOfType.ACCOUNT_VALUE, account)))
                .identical(false)
                .build();
        final var stateChange = StateChange.newBuilder()
                .stateId(StateIdentifier.STATE_ID_ACCOUNTS.protoOrdinal())
                .mapUpdate(change)
                .build();
        return BlockItem.newBuilder()
                .stateChanges(new StateChanges(new Timestamp(1_700_000_000L, 0), List.of(stateChange)))
                .build();
    }

    private static AccountID accountId(final long num) {
        return AccountID.newBuilder().accountNum(num).build();
    }
}
