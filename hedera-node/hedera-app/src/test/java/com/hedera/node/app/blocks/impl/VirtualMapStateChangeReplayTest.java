// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl;

import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_ACCOUNTS;
import static com.hedera.node.app.service.clpr.impl.schemas.V0780ClprSchema.ENDPOINT_MANIFEST_CONSTRUCTION_STATE_ID;
import static com.swirlds.state.test.fixtures.merkle.VirtualMapStateTestUtils.createTestState;
import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifestConstruction;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.node.app.hapi.utils.blocks.StateChangeDeltas;
import com.hedera.node.app.state.WrappedState;
import com.swirlds.state.lifecycle.StateDefinition;
import com.swirlds.state.lifecycle.StateMetadata;
import com.swirlds.state.merkle.VirtualMapStateImpl;
import com.swirlds.state.test.fixtures.merkle.MerkleTestBase;
import com.swirlds.virtualmap.VirtualMap;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real storage, listener bridge, serialization, byte replay, and root comparison without a running network. */
class VirtualMapStateChangeReplayTest extends MerkleTestBase {
    private static final String TOKEN_SERVICE = "TokenService";
    private static final String CLPR_SERVICE = "ClprService";
    private static final int ACCOUNTS = STATE_ID_ACCOUNTS.protoOrdinal();
    private static final AccountID KEY = AccountID.newBuilder().accountNum(123).build();

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void initializedSingletonAndMapChangesReproduceTheVirtualMapRoot(final boolean deltasEnabled) {
        final var producer = createTestState(FILE_SYSTEM_MANAGER);
        final var replay = createTestState(FILE_SYSTEM_MANAGER);
        VirtualMap producerFork = null;
        VirtualMap replayFork = null;
        try {
            initialize(producer);
            initialize(replay);
            final var listener = new ImmediateStateChangeListener();
            producer.registerCommitListener(listener);
            listener.beginBlock(deltasEnabled, 0);
            // Handle-time setup inserts the singleton in a separate transaction before ordinary map changes.
            final var setup = new WrappedState(producer);
            setup.getWritableStates(CLPR_SERVICE)
                    .<ClprEndpointManifestConstruction>getSingleton(ENDPOINT_MANIFEST_CONSTRUCTION_STATE_ID)
                    .put(ClprEndpointManifestConstruction.DEFAULT);
            setup.commit();
            final var initialization = serialized(listener.getStateChanges());
            assertThat(initialization)
                    .extracting(StateChange::stateId)
                    .containsExactly(ENDPOINT_MANIFEST_CONSTRUCTION_STATE_ID);
            assertThat(initialization.getFirst().singletonUpdateOrThrow().partial())
                    .isFalse();
            apply(replay, initialization);
            listener.reset(null);

            final var account = Account.newBuilder()
                    .accountId(KEY)
                    .memo("m".repeat(200))
                    .tinybarBalance(1)
                    .build();
            final var construction = ClprEndpointManifestConstruction.newBuilder()
                    .constructionId(1)
                    .targetNodeIds(LongStream.range(0, 100).boxed().toList())
                    .build();
            commit(producer, account, construction);
            final var insertions = serialized(listener.getStateChanges());
            assertThat(insertions)
                    .extracting(StateChange::stateId)
                    .containsExactly(ACCOUNTS, ENDPOINT_MANIFEST_CONSTRUCTION_STATE_ID);
            apply(replay, insertions);

            // A later block uses values read back through real storage, not the producer's original objects.
            listener.reset(null);
            listener.beginBlock(deltasEnabled, 0);
            final var storedAccount = producer.getReadableStates(TOKEN_SERVICE)
                    .<AccountID, Account>get(ACCOUNTS)
                    .get(KEY);
            final var storedConstruction = producer.getReadableStates(CLPR_SERVICE)
                    .<ClprEndpointManifestConstruction>getSingleton(ENDPOINT_MANIFEST_CONSTRUCTION_STATE_ID)
                    .get();
            commit(
                    producer,
                    storedAccount.copyBuilder().tinybarBalance(2).build(),
                    storedConstruction.copyBuilder().graceExtensionsUsed(1).build());
            final var updates = serialized(listener.getStateChanges());
            assertThat(updates).allSatisfy(change -> assertThat(
                            change.hasMapUpdate()
                                    ? change.mapUpdateOrThrow().partial()
                                    : change.singletonUpdateOrThrow().partial())
                    .isEqualTo(deltasEnabled));
            apply(replay, updates);

            // Freezing each map makes its root hash available while retaining a mutable fork for lifecycle cleanup.
            producerFork = producer.getRoot().copy();
            replayFork = replay.getRoot().copy();
            producer.computeHash();
            replay.computeHash();
            assertThat(replay.getHash()).isEqualTo(producer.getHash());
            assertThat(replay.getSingletonPath(ENDPOINT_MANIFEST_CONSTRUCTION_STATE_ID))
                    .isEqualTo(producer.getSingletonPath(ENDPOINT_MANIFEST_CONSTRUCTION_STATE_ID));
            assertThat(replay.getKvPath(ACCOUNTS, KEY, AccountID.PROTOBUF))
                    .isEqualTo(producer.getKvPath(ACCOUNTS, KEY, AccountID.PROTOBUF));
        } finally {
            producer.release();
            replay.release();
            if (producerFork != null) producerFork.release();
            if (replayFork != null) replayFork.release();
        }
    }

    private static void initialize(final VirtualMapStateImpl state) {
        state.initializeState(new StateMetadata<>(
                TOKEN_SERVICE, StateDefinition.keyValue(ACCOUNTS, "ACCOUNTS", AccountID.PROTOBUF, Account.PROTOBUF)));
        state.initializeState(new StateMetadata<>(
                CLPR_SERVICE,
                StateDefinition.singleton(
                        ENDPOINT_MANIFEST_CONSTRUCTION_STATE_ID,
                        "CONSTRUCTION",
                        ClprEndpointManifestConstruction.PROTOBUF)));
    }

    private static void commit(
            final VirtualMapStateImpl state,
            final Account account,
            final ClprEndpointManifestConstruction construction) {
        final var wrapped = new WrappedState(state);
        wrapped.getWritableStates(CLPR_SERVICE)
                .<ClprEndpointManifestConstruction>getSingleton(ENDPOINT_MANIFEST_CONSTRUCTION_STATE_ID)
                .put(construction);
        wrapped.getWritableStates(TOKEN_SERVICE)
                .<AccountID, Account>get(ACCOUNTS)
                .put(KEY, account);
        wrapped.commit();
    }

    private static List<StateChange> serialized(final List<StateChange> changes) {
        return changes.stream()
                .map(change -> {
                    try {
                        return StateChange.PROTOBUF.parse(StateChange.PROTOBUF.toBytes(change));
                    } catch (com.hedera.pbj.runtime.ParseException e) {
                        throw new AssertionError(e);
                    }
                })
                .toList();
    }

    private static void apply(final VirtualMapStateImpl state, final List<StateChange> changes) {
        for (final var change : changes) {
            if (change.hasMapUpdate()) {
                final var update = change.mapUpdateOrThrow();
                final var type = StateChangeDeltas.mapType(
                        update.valueOrThrow().valueChoice().kind().protoOrdinal());
                final var key = AccountID.PROTOBUF.toBytes(update.keyOrThrow().accountIdKeyOrThrow());
                state.updateKv(
                        change.stateId(),
                        key,
                        StateChangeDeltas.applyBytes(
                                update.partial() ? state.getKv(change.stateId(), key) : null,
                                type.bytes(update.valueOrThrow().valueChoice().value()),
                                update.partial(),
                                update.clearedFields()));
            } else {
                final var update = change.singletonUpdateOrThrow();
                final var type =
                        StateChangeDeltas.singletonType(update.newValue().kind().protoOrdinal());
                state.updateSingleton(
                        change.stateId(),
                        StateChangeDeltas.applyBytes(
                                update.partial() ? state.getSingleton(change.stateId()) : null,
                                type.bytes(update.newValue().value()),
                                update.partial(),
                                update.clearedFields()));
            }
        }
    }
}
