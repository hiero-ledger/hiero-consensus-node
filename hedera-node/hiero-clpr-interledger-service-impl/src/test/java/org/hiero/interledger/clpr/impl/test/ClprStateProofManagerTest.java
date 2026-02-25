// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr.impl.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hedera.hapi.block.stream.StateProof;
import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.hedera.hapi.platform.state.PlatformState;
import com.hedera.node.app.spi.state.BlockProvenSnapshotProvider;
import com.hedera.node.app.state.BlockProvenStateAccessor;
import com.hedera.node.config.data.ClprConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.StateLifecycleManager;
import com.swirlds.state.lifecycle.StateDefinition;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.state.spi.CommittableWritableStates;
import com.swirlds.state.test.fixtures.merkle.VirtualMapStateTestUtils;
import org.hiero.consensus.platformstate.PlatformStateService;
import org.hiero.consensus.platformstate.V0540PlatformStateSchema;
import org.hiero.hapi.interledger.clpr.ClprSetLedgerConfigurationTransactionBody;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerConfiguration;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerId;
import org.hiero.hapi.interledger.state.clpr.ClprLocalLedgerMetadata;
import org.hiero.interledger.clpr.ClprService;
import org.hiero.interledger.clpr.ClprStateProofUtils;
import org.hiero.interledger.clpr.impl.ClprStateProofManager;
import org.hiero.interledger.clpr.impl.schemas.V0700ClprSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ClprStateProofManagerTest extends ClprTestBase {

    private ClprStateProofManager manager;
    private BlockProvenStateAccessor snapshotProvider;
    private StateLifecycleManager stateLifecycleManager;
    private VirtualMapState testState;
    private ClprConfig devModeConfig;

    @BeforeEach
    void setUp() {
        setupBase();
        testState = buildMerkleStateWithConfigurations(
                configurationMap,
                ClprLocalLedgerMetadata.newBuilder().ledgerId(localClprLedgerId).build());
        stateLifecycleManager = mock(StateLifecycleManager.class);
        when(stateLifecycleManager.getLatestImmutableState()).thenReturn(testState);
        snapshotProvider = new BlockProvenStateAccessor(stateLifecycleManager);
        // Create dev mode config for testing
        devModeConfig = new ClprConfig(true, 5000, true, true);
        manager = new ClprStateProofManager(snapshotProvider, devModeConfig);
    }

    private com.swirlds.state.lifecycle.StateMetadata<ClprLedgerId, ClprLedgerConfiguration>
            ledgerConfigStateMetadata() {
        return new com.swirlds.state.lifecycle.StateMetadata<>(
                ClprService.NAME,
                StateDefinition.keyValue(
                        V0700ClprSchema.CLPR_LEDGER_CONFIGURATIONS_STATE_ID,
                        V0700ClprSchema.CLPR_LEDGER_CONFIGURATIONS_STATE_KEY,
                        ClprLedgerId.PROTOBUF,
                        ClprLedgerConfiguration.PROTOBUF));
    }

    private com.swirlds.state.lifecycle.StateMetadata<Void, ClprLocalLedgerMetadata> ledgerMetadataStateMetadata() {
        return new com.swirlds.state.lifecycle.StateMetadata<>(
                ClprService.NAME,
                StateDefinition.singleton(
                        V0700ClprSchema.CLPR_LEDGER_METADATA_STATE_ID,
                        V0700ClprSchema.CLPR_LEDGER_METADATA_STATE_KEY,
                        ClprLocalLedgerMetadata.PROTOBUF));
    }

    @Test
    void getLocalLedgerIdReturnsLedgerIdFromHistoryStore() {
        final var localLedgerId = manager.getLocalLedgerId();
        assertNotNull(localLedgerId);
        assertEquals(Bytes.wrap(rawLocalLedgerId), localLedgerId.ledgerId());
    }

    @Test
    void getLocalLedgerIdReturnsEmptyWhenLedgerIdMissing() {
        final var emptyState = buildMerkleStateWithConfigurations(java.util.Map.of());
        final var emptyLifecycleManager = mock(StateLifecycleManager.class);
        when(emptyLifecycleManager.getLatestImmutableState()).thenReturn(emptyState);
        final var emptyAccessor = new BlockProvenStateAccessor(emptyLifecycleManager);
        final var managerWithMissingLedgerId = new ClprStateProofManager(emptyAccessor, devModeConfig);
        final var ledgerId = managerWithMissingLedgerId.getLocalLedgerId();
        assertNotNull(ledgerId);
        assertEquals(ClprLedgerId.DEFAULT, ledgerId);
    }

    @Test
    void getLocalLedgerIdUsesMetadataWhenPresent() {
        final var metadata =
                ClprLocalLedgerMetadata.newBuilder().ledgerId(localClprLedgerId).build();
        final var state =
                buildMerkleStateWithConfigurations(java.util.Map.of(remoteClprLedgerId, remoteClprConfig), metadata);
        final var lifecycleManager = mock(StateLifecycleManager.class);
        when(lifecycleManager.getLatestImmutableState()).thenReturn(state);
        final var accessor = new BlockProvenStateAccessor(lifecycleManager);
        final var managerWithMetadata = new ClprStateProofManager(accessor, devModeConfig);

        final var ledgerId = managerWithMetadata.getLocalLedgerId();
        assertNotNull(ledgerId);
        assertEquals(localClprLedgerId, ledgerId);
    }

    @Test
    void getLedgerConfigurationReturnsConfigWhenFound() {
        final var proof = manager.getLedgerConfiguration(remoteClprLedgerId);
        assertNotNull(proof);
        assertEquals(remoteClprConfig, ClprStateProofUtils.extractConfiguration(proof));
    }

    @Test
    void readAllLedgerConfigurationsReturnsAllStoredConfigurations() {
        final var configs = manager.readAllLedgerConfigurations();
        assertEquals(2, configs.size());
        assertEquals(localClprConfig, configs.get(localClprLedgerId));
        assertEquals(remoteClprConfig, configs.get(remoteClprLedgerId));
    }

    @Test
    void getLedgerConfigurationReturnsNullWhenStateUnavailable() {
        final BlockProvenSnapshotProvider emptyProvider = () -> java.util.Optional.empty();
        final var emptyManager = new ClprStateProofManager(emptyProvider, devModeConfig);
        assertNull(emptyManager.getLedgerConfiguration(remoteClprLedgerId));
    }

    @Test
    void getLedgerConfigurationResolvesBlankLedgerIdToLocal() {
        final var proof = manager.getLedgerConfiguration(ClprLedgerId.DEFAULT);
        assertNotNull(proof);
        assertEquals(localClprConfig, ClprStateProofUtils.extractConfiguration(proof));
    }

    @Test
    void isDevModeEnabledReflectsConfig() {
        assertTrue(manager.isDevModeEnabled());
        final var prodConfig = new ClprConfig(true, devModeConfig.connectionFrequency(), true, false);
        final var prodManager = new ClprStateProofManager(snapshotProvider, prodConfig);
        assertFalse(prodManager.isDevModeEnabled());
    }

    @Test
    void validateStateProofReturnsTrueForValidProof() {
        final var stateProof = buildLocalClprStateProofWrapper(remoteClprConfig);
        final var txn = validTransaction(stateProof);

        assertTrue(manager.validateStateProof(txn));
    }

    @Test
    void validateStateProofReturnsFalseForInvalidProof() {
        final var stateProof = buildInvalidStateProof(remoteClprConfig);
        final var txn = validTransaction(stateProof);

        assertFalse(manager.validateStateProof(txn));
    }

    @Test
    void validateStateProofReturnsFalseWhenProofMissing() {
        assertFalse(manager.validateStateProof(ClprSetLedgerConfigurationTransactionBody.DEFAULT));
    }

    @Test
    void getLatestConsensusRoundReturnsRoundFromState() {
        final long expectedRound = 42L;
        final var stateWithPlatform = buildMerkleStateWithPlatformRound(expectedRound);
        final var lifecycleManager = mock(StateLifecycleManager.class);
        when(lifecycleManager.getLatestImmutableState()).thenReturn(stateWithPlatform);
        final var accessor = new BlockProvenStateAccessor(lifecycleManager);
        final var managerWithPlatform = new ClprStateProofManager(accessor, devModeConfig);

        assertEquals(expectedRound, managerWithPlatform.getLatestConsensusRound());
    }

    @Test
    void getLatestConsensusRoundReturnsZeroWhenNoSnapshot() {
        final BlockProvenSnapshotProvider emptyProvider = () -> java.util.Optional.empty();
        final var emptyManager = new ClprStateProofManager(emptyProvider, devModeConfig);
        assertEquals(0L, emptyManager.getLatestConsensusRound());
    }

    private ClprSetLedgerConfigurationTransactionBody validTransaction(final StateProof proof) {
        return ClprSetLedgerConfigurationTransactionBody.newBuilder()
                .ledgerConfigurationProof(proof)
                .build();
    }

    private VirtualMapState buildMerkleStateWithConfigurations(
            final java.util.Map<ClprLedgerId, ClprLedgerConfiguration> configurations) {
        return buildMerkleStateWithConfigurations(configurations, null);
    }

    private VirtualMapState buildMerkleStateWithConfigurations(
            final java.util.Map<ClprLedgerId, ClprLedgerConfiguration> configurations,
            final ClprLocalLedgerMetadata metadata) {
        final var state = VirtualMapStateTestUtils.createTestState();
        state.initializeState(ledgerConfigStateMetadata());
        state.initializeState(ledgerMetadataStateMetadata());
        final var writableStates = state.getWritableStates(ClprService.NAME);
        final var writableConfigurations = writableStates.<ClprLedgerId, ClprLedgerConfiguration>get(
                V0700ClprSchema.CLPR_LEDGER_CONFIGURATIONS_STATE_ID);
        configurations.forEach(writableConfigurations::put);
        if (metadata != null) {
            final var writableMetadata =
                    writableStates.<ClprLocalLedgerMetadata>getSingleton(V0700ClprSchema.CLPR_LEDGER_METADATA_STATE_ID);
            writableMetadata.put(metadata);
        }
        if (writableStates instanceof CommittableWritableStates committableStates) {
            committableStates.commit();
        }
        // Make the state immutable and compute hashes so Merkle proofs can be generated
        state.copy();
        state.computeHash();
        return state;
    }

    private com.swirlds.state.lifecycle.StateMetadata<Void, PlatformState> platformStateMetadata() {
        return new com.swirlds.state.lifecycle.StateMetadata<>(
                PlatformStateService.NAME,
                StateDefinition.singleton(
                        V0540PlatformStateSchema.PLATFORM_STATE_STATE_ID,
                        V0540PlatformStateSchema.PLATFORM_STATE_KEY,
                        PlatformState.PROTOBUF));
    }

    private VirtualMapState buildMerkleStateWithPlatformRound(final long round) {
        final var state = VirtualMapStateTestUtils.createTestState();
        state.initializeState(ledgerConfigStateMetadata());
        state.initializeState(ledgerMetadataStateMetadata());
        state.initializeState(platformStateMetadata());
        final var writableStates = state.getWritableStates(PlatformStateService.NAME);
        final var writablePlatformState =
                writableStates.<PlatformState>getSingleton(V0540PlatformStateSchema.PLATFORM_STATE_STATE_ID);
        writablePlatformState.put(PlatformState.newBuilder()
                .consensusSnapshot(
                        ConsensusSnapshot.newBuilder().round(round).build())
                .build());
        if (writableStates instanceof CommittableWritableStates committableStates) {
            committableStates.commit();
        }
        state.copy();
        state.computeHash();
        return state;
    }
}
