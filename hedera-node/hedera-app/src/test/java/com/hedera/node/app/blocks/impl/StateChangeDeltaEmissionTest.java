// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl;

import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_ACCOUNTS;
import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_CLPR_ENDPOINT_MANIFEST;
import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_PLATFORM_STATE;
import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_THROTTLE_USAGE_SNAPSHOTS;
import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.block.stream.output.StateChanges;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.state.blockstream.BlockStreamInfo;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifest;
import com.hedera.hapi.node.state.entity.EntityCounts;
import com.hedera.hapi.node.state.throttles.ThrottleUsageSnapshot;
import com.hedera.hapi.node.state.throttles.ThrottleUsageSnapshots;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.hedera.hapi.platform.state.PlatformState;
import com.hedera.node.app.hapi.utils.blocks.StateChangeDeltas;
import com.hedera.node.app.metrics.StoreMetricsServiceImpl;
import com.hedera.node.app.service.entityid.impl.schemas.V0590EntityIdSchema;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.hiero.consensus.fakes.noop.NoOpMetrics;
import org.junit.jupiter.api.Test;

class StateChangeDeltaEmissionTest {

    @Test
    void stringStateWithoutDiffApisEmitsASerializableFullValue() throws Exception {
        final var listener = new ImmediateStateChangeListener();
        listener.beginBlock(true, 0);
        final var prior = new com.hedera.hapi.node.state.primitives.ProtoString("old");
        final var next = new com.hedera.hapi.node.state.primitives.ProtoString("new");
        listener.mapUpdateChange(0, KEY, prior, next);
        final var change = listener.getKvStateChanges().getFirst();
        assertThat(change.mapUpdateOrThrow().partial()).isFalse();
        assertThat(change.mapUpdateOrThrow().valueOrThrow().protoStringValue()).isEqualTo(next.value());
        assertThat(StateChange.PROTOBUF.parse(StateChange.PROTOBUF.toBytes(change)))
                .isEqualTo(change);
    }

    @Test
    void generatedCodecEnablesMapDeltas() {
        final var listener = new ImmediateStateChangeListener();
        listener.beginBlock(true, 0);
        assertThat(listener.requiresPreviousValue(ACCOUNTS)).isTrue();
        listener.mapUpdateChange(ACCOUNTS, KEY, A, B);
        final var update = listener.getKvStateChanges().getFirst().mapUpdateOrThrow();
        assertThat(update.partial()).isTrue();
        assertThat(StateChangeDeltas.applyMap(ACCOUNTS, A, update)).isEqualTo(B);
    }

    @Test
    void encodesUsingThePreCommitValueAfterStorageProvidesAnEqualCopy() throws Exception {
        final var root =
                Account.newBuilder().memo("m".repeat(100)).tinybarBalance(1).build();
        final var first = root.copyBuilder().tinybarBalance(2).build();
        final var storedFirst = Account.PROTOBUF.parse(Account.PROTOBUF.toBytes(first));
        final var second = storedFirst.copyBuilder().tinybarBalance(3).build();
        final var listener = new ImmediateStateChangeListener();
        listener.beginBlock(true);
        listener.mapUpdateChange(ACCOUNTS, KEY, root, first);
        final var firstUpdate = listener.getKvStateChanges().getFirst().mapUpdateOrThrow();
        assertThat(firstUpdate.partial()).isTrue();
        assertThat(StateChangeDeltas.applyMap(ACCOUNTS, root, firstUpdate)).isEqualTo(first);
        listener.resetKvStateChanges(null);
        listener.mapUpdateChange(ACCOUNTS, KEY, storedFirst, second);
        final var secondUpdate = listener.getKvStateChanges().getFirst().mapUpdateOrThrow();
        assertThat(secondUpdate.partial()).isTrue();
        assertThat(StateChangeDeltas.applyMap(ACCOUNTS, storedFirst, secondUpdate))
                .isEqualTo(second);
        assertThat(storedFirst).isEqualTo(first).isNotSameAs(first);
    }

    private static final int ACCOUNTS = STATE_ID_ACCOUNTS.protoOrdinal();
    private static final AccountID KEY = AccountID.newBuilder().accountNum(123).build();
    private static final Account A = Account.newBuilder()
            .accountId(KEY)
            .key(com.hedera.hapi.node.base.Key.newBuilder().ed25519(Bytes.wrap("k".repeat(100))))
            .tinybarBalance(10)
            .memo("a")
            .build();
    private static final Account B = A.copyBuilder().tinybarBalance(20).build();
    private static final Account C = B.copyBuilder().memo("c").build();

    @Test
    void independentlyParsedNodesProduceIdenticalBlockItemBytes() throws Exception {
        final var cached = new ImmediateStateChangeListener();
        final var parsed = new ImmediateStateChangeListener();
        cached.beginBlock(true);
        parsed.beginBlock(true);
        cached.mapUpdateChange(ACCOUNTS, KEY, A, C);
        parsed.mapUpdateChange(
                ACCOUNTS,
                AccountID.PROTOBUF.parse(AccountID.PROTOBUF.toBytes(KEY)),
                Account.PROTOBUF.parse(Account.PROTOBUF.toBytes(A)),
                Account.PROTOBUF.parse(Account.PROTOBUF.toBytes(C)));
        assertThat(BlockItem.PROTOBUF.toBytes(item(cached.getKvStateChanges())))
                .isEqualTo(BlockItem.PROTOBUF.toBytes(item(parsed.getKvStateChanges())));
    }

    @Test
    void orderedCommitsReplayWithStaleLineageClearsAndNoOp() {
        final var listener = new ImmediateStateChangeListener();
        listener.beginBlock(true);
        listener.reset(key -> true);
        listener.mapUpdateChange(ACCOUNTS, KEY, A, B);
        listener.mapUpdateChange(ACCOUNTS, KEY, B, C);
        final var cleared = C.copyBuilder().memo("").build();
        listener.mapUpdateChange(ACCOUNTS, KEY, C, cleared);
        Object replay = A;
        for (final var change : listener.getKvStateChanges()) {
            final var update = change.mapUpdateOrThrow();
            assertThat(update.partial()).isTrue();
            assertThat(update.identical()).isTrue();
            assertThat(com.hedera.pbj.runtime.CopyBuilderTracked.untracked(update))
                    .isSameAs(update);
            assertThat(update.valueOrThrow().accountValueOrThrow().$copyBuilderOrigin())
                    .isNull();
            replay = StateChangeDeltas.applyMap(ACCOUNTS, replay, update);
        }
        assertThat(replay).isEqualTo(cleared);
        assertThat(listener.getKvStateChanges().getLast().mapUpdateOrThrow().clearedFields())
                .containsExactly(6);
        listener.reset(null);
        listener.mapUpdateChange(ACCOUNTS, KEY, cleared, cleared);
        final var noop = listener.getKvStateChanges().getFirst().mapUpdateOrThrow();
        assertThat(noop.partial()).isTrue();
        assertThat(noop.identical()).isFalse();
        assertThat(noop.valueOrThrow().accountValueOrThrow()).isEqualTo(Account.DEFAULT);
        assertThat(StateChangeDeltas.applyMap(ACCOUNTS, cleared, noop)).isEqualTo(cleared);
    }

    @Test
    void disabledModePreservesBytesAndDoesNotRequestOldValues() {
        final var listener = new ImmediateStateChangeListener();
        listener.beginBlock(false);
        listener.mapUpdateChange(ACCOUNTS, KEY, A, B);
        final var legacy = new ImmediateStateChangeListener();
        legacy.mapUpdateChange(ACCOUNTS, KEY, B);
        assertThat(listener.requiresPreviousValue()).isFalse();
        assertThat(listener.requiresPreviousValue(ACCOUNTS)).isFalse();
        assertThat(listener.getKvStateChanges().getFirst().mapUpdateOrThrow().partial())
                .isFalse();
        assertThat(BlockItem.PROTOBUF.toBytes(item(listener.getKvStateChanges())))
                .isEqualTo(BlockItem.PROTOBUF.toBytes(item(legacy.getKvStateChanges())));
    }

    @Test
    void fullWireValuesDoNotRetainPreviousValuesThroughBuilderProvenance() {
        final var root = Account.newBuilder().tinybarBalance(1).build();
        final var next = root.copyBuilder().tinybarBalance(2).build();
        assertThat(next.$copyBuilderOrigin()).isSameAs(root);
        final var listener = new ImmediateStateChangeListener();
        listener.beginBlock(true);
        listener.mapUpdateChange(ACCOUNTS, KEY, root, next);
        final var update = listener.getKvStateChanges().getFirst().mapUpdateOrThrow();
        assertThat(update.partial()).isFalse();
        assertThat(com.hedera.pbj.runtime.CopyBuilderTracked.untracked(update)).isSameAs(update);
        assertThat(update.valueOrThrow().accountValueOrThrow()).isEqualTo(next);
        assertThat(update.valueOrThrow().accountValueOrThrow().$copyBuilderOrigin())
                .isNull();
    }

    @Test
    void deleteRecreateAndNextBlockUseTheCallbackBaseline() {
        final var listener = new ImmediateStateChangeListener();
        listener.beginBlock(true);
        listener.mapUpdateChange(ACCOUNTS, KEY, A, B);
        final var initial = listener.getKvStateChanges().getFirst().mapUpdateOrThrow();
        assertThat(StateChangeDeltas.applyMap(ACCOUNTS, A, initial)).isEqualTo(B);
        listener.reset(null);
        listener.mapDeleteChange(ACCOUNTS, KEY);
        listener.mapUpdateChange(ACCOUNTS, KEY, null, C);
        assertThat(listener.getKvStateChanges().getFirst().hasMapDelete()).isTrue();
        final var recreated = listener.getKvStateChanges().get(1).mapUpdateOrThrow();
        assertThat(recreated.partial()).isFalse();
        assertThat(StateChangeDeltas.applyMap(ACCOUNTS, null, recreated)).isEqualTo(C);
        listener.beginBlock(true);
        listener.reset(null);
        listener.mapUpdateChange(ACCOUNTS, KEY, C, A);
        final var nextBlock = listener.getKvStateChanges().getFirst().mapUpdateOrThrow();
        assertThat(nextBlock.partial()).isTrue();
        assertThat(StateChangeDeltas.applyMap(ACCOUNTS, C, nextBlock)).isEqualTo(A);
    }

    @Test
    void updateWithoutAPreviousValueCallbackStaysFull() {
        final var listener = new ImmediateStateChangeListener();
        listener.beginBlock(true);
        listener.mapUpdateChange(ACCOUNTS, KEY, C);
        final var update = listener.getKvStateChanges().getFirst().mapUpdateOrThrow();
        assertThat(update.partial()).isFalse();
        assertThat(StateChangeDeltas.applyMap(ACCOUNTS, null, update)).isEqualTo(C);
    }

    @Test
    void coalescesSingletonsAgainstFirstPriorAndDrainsAtFlush() {
        final var listener = boundaryListener();
        listener.beginBlock(true);
        final int stateId = STATE_ID_THROTTLE_USAGE_SNAPSHOTS.protoOrdinal();
        final var a = ThrottleUsageSnapshots.newBuilder()
                .tpsThrottles(java.util.Collections.nCopies(32, new ThrottleUsageSnapshot(1, null)))
                .gasThrottle(ThrottleUsageSnapshot.newBuilder().used(10).build())
                .build();
        final var b = a.copyBuilder()
                .gasThrottle(ThrottleUsageSnapshot.newBuilder().used(20).build())
                .build();
        final var c = b.copyBuilder()
                .gasThrottle(ThrottleUsageSnapshot.newBuilder().used(30).build())
                .build();
        listener.singletonUpdateChange(stateId, a, b);
        listener.singletonUpdateChange(stateId, b, c);
        final var changes = listener.allStateChanges();
        final var update = changes.getFirst().singletonUpdateOrThrow();
        assertThat(update.partial()).isTrue();
        assertThat(StateChangeDeltas.applySingleton(stateId, a, update)).isEqualTo(c);
        listener.stateChangesEmitted(changes);
        listener.singletonUpdateChange(stateId, c, a);
        listener.singletonUpdateChange(stateId, a, c);
        final var noop = listener.allStateChanges().getFirst().singletonUpdateOrThrow();
        assertThat(noop.partial()).isTrue();
        assertThat(noop.throttleUsageSnapshotsValueOrThrow()).isEqualTo(ThrottleUsageSnapshots.DEFAULT);
        assertThat(StateChangeDeltas.applySingleton(stateId, c, noop)).isEqualTo(c);
    }

    @Test
    void recoverySingletonAndMigrationStayFull() {
        final var listener = boundaryListener();
        final int stateId = STATE_ID_THROTTLE_USAGE_SNAPSHOTS.protoOrdinal();
        listener.singletonUpdateChange(stateId, ThrottleUsageSnapshots.DEFAULT, ThrottleUsageSnapshots.DEFAULT);
        assertThat(listener.allStateChanges()
                        .getFirst()
                        .singletonUpdateOrThrow()
                        .partial())
                .isFalse();
        listener.reset();
        listener.beginBlock(true);
        final int blockInfo =
                com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_BLOCK_STREAM_INFO.protoOrdinal();
        final var prior = BlockStreamInfo.newBuilder()
                .trailingBlockHashes(Bytes.wrap("h".repeat(200)))
                .build();
        listener.singletonUpdateChange(
                blockInfo, prior, prior.copyBuilder().blockNumber(123).build());
        assertThat(listener.allStateChanges(true)
                        .getFirst()
                        .singletonUpdateOrThrow()
                        .partial())
                .isTrue();
        assertThat(listener.allStateChanges(false)
                        .getFirst()
                        .singletonUpdateOrThrow()
                        .partial())
                .isFalse();
    }

    private static final int ENTITY_COUNTS = V0590EntityIdSchema.ENTITY_COUNTS_STATE_ID;
    private static final EntityCounts COUNTS = EntityCounts.newBuilder()
            .numAccounts(1_000)
            .numSchedules(10)
            .numFiles(1_000_000)
            .numTokens(1_000_000)
            .numTopics(1_000_000)
            .build();

    @Test
    void singletonBaselineFollowsMidBlockEmissions() {
        final var listener = boundaryListener();
        listener.beginBlock(true, 0);
        final var scheduled = COUNTS.copyBuilder().numSchedules(11).build();
        final var purged =
                scheduled.copyBuilder().numSchedules(10).numAccounts(1_001).build();
        // Emitted mid-block without a reset, as HandleWorkflow.doStreamingChangesInternal does
        listener.singletonUpdateChange(ENTITY_COUNTS, COUNTS, scheduled);
        final var reader = replaySingletons(COUNTS, emitAll(listener));
        assertThat(reader).isEqualTo(scheduled);
        listener.singletonUpdateChange(ENTITY_COUNTS, scheduled, purged);
        final var atClose = listener.allStateChanges();
        assertThat(atClose.getFirst().singletonUpdateOrThrow().partial()).isTrue();
        // num_schedules is back at its block-start value, but readers hold 11
        assertThat(replaySingletons(reader, atClose)).isEqualTo(purged);
    }

    @Test
    void unrelatedMidBlockPublicationPreservesTheFirstPendingBaseline() {
        final var listener = boundaryListener();
        listener.beginBlock(true, 0);
        final var moreFiles = COUNTS.copyBuilder().numFiles(1_000_001).build();
        final var moreAccounts = moreFiles.copyBuilder().numAccounts(1_001).build();
        listener.singletonUpdateChange(ENTITY_COUNTS, COUNTS, moreFiles);
        // Transaction-owned CLPR changes do not disturb a pending boundary baseline.
        listener.singletonUpdateChange(MANIFEST, ClprEndpointManifest.DEFAULT, NEXT_MANIFEST);
        listener.singletonUpdateChange(ENTITY_COUNTS, moreFiles, moreAccounts);
        assertThat(replaySingletons(COUNTS, listener.allStateChanges())).isEqualTo(moreAccounts);
    }

    @Test
    void singletonPendingWhenBlockBeginsOrRecreatedStaysFull() {
        final var listener = boundaryListener();
        listener.beginBlock(true, 0);
        final var first = COUNTS.copyBuilder().numAccounts(1_001).build();
        final var second = first.copyBuilder().numAccounts(1_002).build();
        final var third = second.copyBuilder().numAccounts(1_003).build();
        listener.singletonUpdateChange(ENTITY_COUNTS, COUNTS, first);
        emitAll(listener);
        // Committed between blocks, as PlatformState is before each round; a restarted node has no baseline
        listener.singletonUpdateChange(ENTITY_COUNTS, first, second);
        listener.beginBlock(true, 0);
        listener.singletonUpdateChange(ENTITY_COUNTS, second, third);
        final var pending = listener.allStateChanges().getFirst().singletonUpdateOrThrow();
        assertThat(pending.partial()).isFalse();
        assertThat(StateChangeDeltas.applySingleton(ENTITY_COUNTS, null, pending))
                .isEqualTo(third);
        listener.reset();

        listener.singletonDeleteChange(ENTITY_COUNTS);
        listener.singletonUpdateChange(ENTITY_COUNTS, null, first);
        assertThat(listener.allStateChanges()
                        .getFirst()
                        .singletonUpdateOrThrow()
                        .partial())
                .isFalse();
    }

    private static final int PLATFORM_STATE = STATE_ID_PLATFORM_STATE.protoOrdinal();
    private static final int MANIFEST = STATE_ID_CLPR_ENDPOINT_MANIFEST.protoOrdinal();
    private static final ClprEndpointManifest NEXT_MANIFEST =
            ClprEndpointManifest.newBuilder().version(2).build();

    @Test
    void immediatePublicationLeavesBoundaryUpdatesPending() {
        final var listener = boundaryListener();
        final var immediate = new ImmediateStateChangeListener();
        listener.beginBlock(false);
        listener.singletonUpdateChange(
                ENTITY_COUNTS, COUNTS, COUNTS.copyBuilder().numAccounts(1_001).build());
        listener.singletonUpdateChange(MANIFEST, ClprEndpointManifest.DEFAULT, NEXT_MANIFEST);
        immediate.singletonUpdateChange(MANIFEST, ClprEndpointManifest.DEFAULT, NEXT_MANIFEST);
        assertThat(immediate.getSingletonStateChanges())
                .extracting(StateChange::stateId)
                .containsExactly(MANIFEST);
        assertThat(listener.allStateChanges()).extracting(StateChange::stateId).containsExactly(ENTITY_COUNTS);
    }

    @Test
    void mapEncodingFailureFallsBackToFullValueInsteadOfAbortingCommit() {
        final var listener = new ImmediateStateChangeListener();
        listener.beginBlock(true, 0);
        // A prior of the wrong type makes the encoder throw; the listener must still record the update
        listener.mapUpdateChange(ACCOUNTS, KEY, COUNTS, C);
        final var update = listener.getKvStateChanges().getFirst().mapUpdateOrThrow();
        assertThat(update.partial()).isFalse();
        assertThat(update.clearedFields()).isEmpty();
        assertThat(update.valueOrThrow().accountValueOrThrow()).isEqualTo(C);
    }

    @Test
    void singletonEncodingFailureFallsBackToFullValue() {
        final var listener = new ImmediateStateChangeListener();
        listener.beginBlock(true, 0);
        // A baseline of the wrong type makes the encoder throw at commit time
        listener.singletonUpdateChange(MANIFEST, COUNTS, NEXT_MANIFEST);
        final var changes = listener.getSingletonStateChanges();
        assertThat(changes).hasSize(1);
        final var update = changes.getFirst().singletonUpdateOrThrow();
        assertThat(update.partial()).isFalse();
        assertThat(update.clearedFields()).isEmpty();
        assertThat(update.clprEndpointManifestValueOrThrow()).isEqualTo(NEXT_MANIFEST);
    }

    @Test
    void midBlockPublicationLosesNoSingletonUpdate() {
        final var listener = boundaryListener();
        final Map<Integer, Object> reader = new HashMap<>(Map.of(
                PLATFORM_STATE, platformStateAt(1), ENTITY_COUNTS, COUNTS, MANIFEST, ClprEndpointManifest.DEFAULT));
        // The platform commits PlatformState before each round, so it is pending when the block begins
        listener.singletonUpdateChange(PLATFORM_STATE, platformStateAt(1), platformStateAt(2));
        listener.beginBlock(true, 0);
        final var moreAccounts = COUNTS.copyBuilder().numAccounts(1_001).build();
        listener.singletonUpdateChange(ENTITY_COUNTS, COUNTS, moreAccounts);
        final var immediate = new ImmediateStateChangeListener();
        immediate.beginBlock(true, 0);
        immediate.singletonUpdateChange(MANIFEST, ClprEndpointManifest.DEFAULT, NEXT_MANIFEST);
        final var midBlock = immediate.getSingletonStateChanges();
        assertThat(midBlock).extracting(StateChange::stateId).containsExactly(MANIFEST);
        replay(reader, midBlock);
        // A later round of the same block
        listener.singletonUpdateChange(PLATFORM_STATE, platformStateAt(2), platformStateAt(3));
        final var evenMoreAccounts =
                moreAccounts.copyBuilder().numAccounts(1_002).build();
        listener.singletonUpdateChange(ENTITY_COUNTS, moreAccounts, evenMoreAccounts);
        final var atClose = emitAll(listener);
        replay(reader, atClose);

        assertThat(reader)
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        PLATFORM_STATE, platformStateAt(3), ENTITY_COUNTS, evenMoreAccounts, MANIFEST, NEXT_MANIFEST));
        // Pending when the block began, so still full for nodes that restarted at the block boundary
        assertThat(atClose.stream()
                        .filter(change -> change.stateId() == PLATFORM_STATE)
                        .map(change -> change.singletonUpdateOrThrow().partial()))
                .containsExactly(false);
    }

    @Test
    void pendingSingletonUpdatedBeforeSelectedPublicationIsEmittedWithItsLatestValue() {
        final var listener = boundaryListener();
        listener.beginBlock(true, 0);
        final var first = COUNTS.copyBuilder().numAccounts(1_001).build();
        final var second = first.copyBuilder().numFiles(1_000_001).build();
        listener.singletonUpdateChange(ENTITY_COUNTS, COUNTS, first);
        listener.singletonUpdateChange(ENTITY_COUNTS, first, second);
        final var selected = listener.allStateChanges();
        assertThat(replaySingletons(COUNTS, selected)).isEqualTo(second);
        listener.stateChangesEmitted(selected);
        assertThat(listener.allStateChanges()).isEmpty();
    }

    @Test
    void nodeRestartedAtTheBlockBoundaryEmitsTheSameBytes() {
        final var running = boundaryListener();
        // This node streamed the previous block with deltas enabled
        running.beginBlock(true, 0);
        running.singletonUpdateChange(
                ENTITY_COUNTS, COUNTS, COUNTS.copyBuilder().numFiles(1_000_001).build());
        running.singletonUpdateChange(PLATFORM_STATE, platformStateAt(1), platformStateAt(2));
        emitAll(running);
        final var fromRunning = clprBlockFrom(running);
        final var fromRestarted = clprBlockFrom(boundaryListener());
        assertThat(fromRunning)
                .anyMatch(change -> change.singletonUpdateOrThrow().partial());
        assertThat(BlockItem.PROTOBUF.toBytes(item(fromRestarted)))
                .isEqualTo(BlockItem.PROTOBUF.toBytes(item(fromRunning)));
    }

    @Test
    void inspectingAnUnpublishedSelectionDoesNotAdvanceItsBaseline() {
        final var listener = boundaryListener();
        listener.beginBlock(true, 0);
        final var first = COUNTS.copyBuilder().numAccounts(1_001).build();
        listener.singletonUpdateChange(ENTITY_COUNTS, COUNTS, first);
        // Inspection or a failed publication does not acknowledge this update.
        final var unpublished = listener.allStateChanges();
        final var second = first.copyBuilder().numFiles(1_000_001).build();
        listener.singletonUpdateChange(ENTITY_COUNTS, first, second);
        final var selected = listener.allStateChanges();
        assertThat(replaySingletons(COUNTS, unpublished)).isEqualTo(first);
        assertThat(replaySingletons(COUNTS, selected)).isEqualTo(second);
    }

    // One CLPR-enabled block as HandleWorkflow drives it, from the same committed state
    private static List<StateChange> clprBlockFrom(final BoundaryStateChangeListener listener) {
        final var counts = COUNTS.copyBuilder().numFiles(1_000_001).build();
        listener.singletonUpdateChange(PLATFORM_STATE, platformStateAt(2), platformStateAt(3));
        listener.beginBlock(true, 0);
        listener.singletonUpdateChange(
                ENTITY_COUNTS, counts, counts.copyBuilder().numAccounts(1_001).build());
        final var immediate = new ImmediateStateChangeListener();
        immediate.beginBlock(true, 0);
        immediate.singletonUpdateChange(MANIFEST, ClprEndpointManifest.DEFAULT, NEXT_MANIFEST);
        final var emitted = new ArrayList<>(immediate.getSingletonStateChanges());
        listener.singletonUpdateChange(PLATFORM_STATE, platformStateAt(3), platformStateAt(4));
        emitted.addAll(emitAll(listener));
        return emitted;
    }

    private static List<StateChange> emitAll(final BoundaryStateChangeListener listener) {
        final var changes = listener.allStateChanges();
        listener.stateChangesEmitted(changes);
        return changes;
    }

    private static PlatformState platformStateAt(final long round) {
        return PlatformState.newBuilder()
                .roundsNonAncient(26)
                .consensusSnapshot(ConsensusSnapshot.newBuilder()
                        .round(round)
                        .nextConsensusNumber(round * 1_000)
                        .build())
                .legacyRunningEventHash(Bytes.wrap("running-hash-" + round))
                .build();
    }

    private static void replay(final Map<Integer, Object> reader, final List<StateChange> changes) {
        for (final var change : changes) {
            final var stateId = change.stateId();
            reader.put(
                    stateId,
                    StateChangeDeltas.applySingleton(stateId, reader.get(stateId), change.singletonUpdateOrThrow()));
        }
    }

    private static Object replaySingletons(final Object prior, final List<StateChange> changes) {
        Object value = prior;
        for (final var change : changes) {
            value = StateChangeDeltas.applySingleton(change.stateId(), value, change.singletonUpdateOrThrow());
        }
        return value;
    }

    private static BoundaryStateChangeListener boundaryListener() {
        return new BoundaryStateChangeListener(
                new StoreMetricsServiceImpl(new NoOpMetrics()), HederaTestConfigBuilder::createConfig);
    }

    private static BlockItem item(final List<StateChange> changes) {
        return BlockItem.newBuilder()
                .stateChanges(StateChanges.newBuilder().stateChanges(changes))
                .build();
    }
}
