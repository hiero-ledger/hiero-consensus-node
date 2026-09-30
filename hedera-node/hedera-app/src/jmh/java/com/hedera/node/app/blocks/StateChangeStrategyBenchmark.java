// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.output.*;
import com.hedera.hapi.node.base.*;
import com.hedera.hapi.node.state.contract.SlotValue;
import com.hedera.hapi.node.state.file.File;
import com.hedera.hapi.node.state.throttles.ThrottleUsageSnapshot;
import com.hedera.hapi.node.state.throttles.ThrottleUsageSnapshots;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.AccountCryptoAllowance;
import com.hedera.node.app.hapi.utils.blocks.StateChangeDeltas;
import com.hedera.node.app.hapi.utils.blocks.StateChangeDeltas.ValueType;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.spi.CopyBuilderTracking;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;
import org.openjdk.jmh.annotations.*;

/** Synthetic full-versus-top-level comparison including minimum-saving selection, serialization and hashing. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class StateChangeStrategyBenchmark {
    public static final List<String> FIXTURES = List.of(
            "ACCOUNT_BALANCE",
            "ACCOUNT_ALLOWANCE",
            "ACCOUNT_NESTED",
            "THROTTLE_LIST",
            "SLOT_UPDATE",
            "FILE_APPEND_1M",
            "FILE_MIDDLE_1M",
            "FILE_REWRITE_1M",
            "FILE_MEMO_1M");

    @Param({
        "ACCOUNT_BALANCE",
        "ACCOUNT_ALLOWANCE",
        "ACCOUNT_NESTED",
        "THROTTLE_LIST",
        "SLOT_UPDATE",
        "FILE_APPEND_1M",
        "FILE_MIDDLE_1M",
        "FILE_REWRITE_1M",
        "FILE_MEMO_1M"
    })
    public String fixture;

    @Param({"FULL", "FIELD"})
    public String strategy;

    @Param({"OBJECT", "COPY_BUILDER"})
    public String priorMode;

    private Object prior;
    private Object next;
    private ValueType type;
    private Bytes priorBytes;
    private Bytes nextBytes;
    private StateChange full;
    private BlockItem fullItem;
    private StateChange encoded;
    private Bytes encodedChangeBytes;
    private MessageDigest digest;

    @Setup
    public void setup() throws Exception {
        final var random = new Random(76234);
        final var owner = AccountID.newBuilder().accountNum(1234).build();
        if (fixture.startsWith("FILE_")) {
            type = StateChangeDeltas.valueType(File.DEFAULT);
            final var bytes = randomBytes(random, 1024 * 1024);
            final var before = File.newBuilder()
                    .fileId(FileID.newBuilder().fileNum(1234))
                    .memo("benchmark file")
                    .contents(Bytes.wrap(bytes))
                    .build();
            final var after = before.copyBuilder();
            switch (fixture) {
                case "FILE_APPEND_1M" -> {
                    final var grown = Arrays.copyOf(bytes, bytes.length + 1024);
                    System.arraycopy(randomBytes(random, 1024), 0, grown, bytes.length, 1024);
                    after.contents(Bytes.wrap(grown));
                }
                case "FILE_MIDDLE_1M" -> {
                    final var changed = bytes.clone();
                    System.arraycopy(randomBytes(random, 100), 0, changed, bytes.length / 2, 100);
                    after.contents(Bytes.wrap(changed));
                }
                case "FILE_REWRITE_1M" -> after.contents(Bytes.wrap(randomBytes(random, bytes.length)));
                case "FILE_MEMO_1M" -> after.memo("changed memo");
                default -> throw new IllegalArgumentException(fixture);
            }
            prior = before;
            next = after.build();
            full = StateChange.newBuilder()
                    .stateId(6)
                    .mapUpdate(MapUpdateChange.newBuilder()
                            .key(MapChangeKey.newBuilder().fileIdKey(before.fileId()))
                            .value(MapChangeValue.newBuilder().fileValue((File) next)))
                    .build();
        } else if (fixture.equals("THROTTLE_LIST")) {
            type = StateChangeDeltas.valueType(ThrottleUsageSnapshots.DEFAULT);
            final var list = new ArrayList<ThrottleUsageSnapshot>();
            for (int i = 0; i < 128; i++)
                list.add(new ThrottleUsageSnapshot(
                        i * 100L,
                        Timestamp.newBuilder().seconds(1_000_000).nanos(i).build()));
            prior = ThrottleUsageSnapshots.newBuilder()
                    .tpsThrottles(List.copyOf(list))
                    .build();
            list.set(64, list.get(64).copyBuilder().used(123456).build());
            next = ((ThrottleUsageSnapshots) prior)
                    .copyBuilder()
                    .tpsThrottles(List.copyOf(list))
                    .build();
            full = StateChange.newBuilder()
                    .stateId(12)
                    .singletonUpdate(SingletonUpdateChange.newBuilder()
                            .throttleUsageSnapshotsValue((ThrottleUsageSnapshots) next))
                    .build();
        } else if (fixture.equals("SLOT_UPDATE")) {
            type = StateChangeDeltas.valueType(SlotValue.DEFAULT);
            final var before = new SlotValue(
                    Bytes.wrap(randomBytes(random, 32)),
                    Bytes.wrap(randomBytes(random, 32)),
                    Bytes.wrap(randomBytes(random, 32)));
            prior = before;
            next = before.copyBuilder()
                    .value(Bytes.wrap(randomBytes(random, 32)))
                    .build();
            full = StateChange.newBuilder()
                    .stateId(4)
                    .mapUpdate(MapUpdateChange.newBuilder()
                            .key(MapChangeKey.newBuilder()
                                    .slotKeyKey(com.hedera.hapi.node.state.contract.SlotKey.DEFAULT))
                            .value(MapChangeValue.newBuilder().slotValueValue((SlotValue) next)))
                    .build();
        } else {
            type = StateChangeDeltas.valueType(Account.DEFAULT);
            var before = Account.newBuilder()
                    .accountId(owner)
                    .tinybarBalance(1_000_000)
                    .memo("account benchmark memo")
                    .expirationSecond(2_000_000_000)
                    .autoRenewSeconds(7_776_000)
                    .build();
            if (fixture.equals("ACCOUNT_ALLOWANCE")) {
                final var allowances = new ArrayList<AccountCryptoAllowance>();
                for (int i = 0; i < 128; i++)
                    allowances.add(new AccountCryptoAllowance(
                            AccountID.newBuilder().accountNum(2000 + i).build(), i + 1));
                before = CopyBuilderTracking.untracked(before.copyBuilder()
                        .cryptoAllowances(List.copyOf(allowances))
                        .build());
                allowances.set(
                        64, allowances.get(64).copyBuilder().amount(123456).build());
                next = before.copyBuilder()
                        .cryptoAllowances(List.copyOf(allowances))
                        .build();
            } else if (fixture.equals("ACCOUNT_NESTED")) {
                final var keys = new ArrayList<Key>();
                for (int i = 0; i < 32; i++)
                    keys.add(Key.newBuilder()
                            .ed25519(Bytes.wrap(randomBytes(random, 32)))
                            .build());
                before = CopyBuilderTracking.untracked(before.copyBuilder()
                        .key(Key.newBuilder()
                                .thresholdKey(
                                        ThresholdKey.newBuilder().threshold(1).keys(new KeyList(keys))))
                        .build());
                next = before.copyBuilder()
                        .key(Key.newBuilder()
                                .thresholdKey(before.keyOrThrow()
                                        .thresholdKeyOrThrow()
                                        .copyBuilder()
                                        .threshold(2)))
                        .build();
            } else next = before.copyBuilder().tinybarBalance(1_000_001).build();
            prior = before;
            full = StateChange.newBuilder()
                    .stateId(2)
                    .mapUpdate(MapUpdateChange.newBuilder()
                            .key(MapChangeKey.newBuilder().accountIdKey(owner))
                            .value(MapChangeValue.newBuilder().accountValue((Account) next)))
                    .build();
        }
        priorBytes = type.bytes(prior);
        nextBytes = type.bytes(next);
        if (priorBytes.equals(nextBytes)) throw new IllegalStateException("Fixture must change state: " + fixture);
        // COPY_BUILDER preserves object sharing; TRACKED additionally requires PBJ provenance.
        if (priorMode.equals("OBJECT") || priorMode.equals("READ")) {
            prior = type.parse(priorBytes);
            full = StateChange.PROTOBUF.parse(StateChange.PROTOBUF.toBytes(full));
        } else if (priorMode.equals("TRACKED")) {
            if (!CopyBuilderTracking.hasOrigin(next, prior)) {
                throw new IllegalStateException(
                        "TRACKED requires an enabled PBJ tracking adapter and exact baseline origin: " + fixture);
            }
        } else if (!priorMode.equals("COPY_BUILDER")) {
            throw new IllegalArgumentException("Unknown prior mode: " + priorMode);
        }
        fullItem = item(full);
        digest = MessageDigest.getInstance("SHA-384");
        encoded = encode();
        encodedChangeBytes = StateChange.PROTOBUF.toBytes(encoded);
        if (!replayObject().equals(next)) throw new IllegalStateException("Object replay mismatch: " + fixture);
        final Bytes rebuilt;
        if (encoded.hasMapUpdate()) {
            final var u = encoded.mapUpdateOrThrow();
            rebuilt = StateChangeDeltas.applyBytes(
                    type,
                    priorBytes,
                    type.bytes(u.valueOrThrow().valueChoice().value()),
                    u.partial(),
                    u.clearedFields());
        } else {
            final var u = encoded.singletonUpdateOrThrow();
            rebuilt = StateChangeDeltas.applyBytes(
                    type, priorBytes, type.bytes(u.newValue().value()), u.partial(), u.clearedFields());
        }
        if (!rebuilt.equals(nextBytes)) throw new IllegalStateException("Byte replay mismatch: " + fixture);
    }

    private StateChange encode() {
        if (strategy.equals("FULL")) return full;
        final var baseline = priorMode.equals("READ") ? type.parse(priorBytes) : prior;
        return full.hasMapUpdate()
                ? full.copyBuilder()
                        .mapUpdate(StateChangeDeltas.encodeMap(full.stateId(), baseline, full.mapUpdateOrThrow()))
                        .build()
                : full.copyBuilder()
                        .singletonUpdate(StateChangeDeltas.encodeSingleton(
                                full.stateId(), baseline, full.singletonUpdateOrThrow()))
                        .build();
    }

    @Benchmark
    public byte[] producerEncodeAndHash() {
        final var bytes = BlockItem.PROTOBUF.toBytes(strategy.equals("FULL") ? fullItem : item(encode()));
        digest.reset();
        bytes.writeTo(digest);
        return digest.digest();
    }

    @Benchmark
    public Object replayObject() throws Exception {
        final var change = StateChange.PROTOBUF.parse(encodedChangeBytes);
        return change.hasMapUpdate()
                ? StateChangeDeltas.applyMap(change.stateId(), prior, change.mapUpdateOrThrow())
                : StateChangeDeltas.applySingleton(change.stateId(), prior, change.singletonUpdateOrThrow());
    }

    private static BlockItem item(final StateChange change) {
        return BlockItem.newBuilder()
                .stateChanges(StateChanges.newBuilder()
                        .consensusTimestamp(Timestamp.newBuilder().seconds(1_000_000))
                        .stateChanges(change))
                .build();
    }

    private static byte[] randomBytes(final Random random, final int size) {
        final var bytes = new byte[size];
        random.nextBytes(bytes);
        return bytes;
    }

    public static void main(final String[] args) throws Exception {
        System.out.println("fixture,strategy,full_item_bytes,emitted_item_bytes,gzip_item_bytes,partial");
        for (final var fixture : FIXTURES)
            for (final var strategy : List.of("FULL", "FIELD")) {
                final var b = new StateChangeStrategyBenchmark();
                b.fixture = fixture;
                b.strategy = strategy;
                b.priorMode = args.length == 0 ? "OBJECT" : args[0];
                b.setup();
                final var wire = BlockItem.PROTOBUF.toBytes(item(b.encoded));
                final var compressed = new ByteArrayOutputStream();
                try (final var gzip = new GZIPOutputStream(compressed)) {
                    gzip.write(wire.toByteArray());
                }
                final boolean partial = b.encoded.hasMapUpdate()
                        ? b.encoded.mapUpdateOrThrow().partial()
                        : b.encoded.singletonUpdateOrThrow().partial();
                System.out.printf(
                        "%s,%s,%d,%d,%d,%s%n",
                        fixture,
                        strategy,
                        BlockItem.PROTOBUF.measureRecord(b.fullItem),
                        wire.length(),
                        compressed.size(),
                        partial);
            }
    }
}
