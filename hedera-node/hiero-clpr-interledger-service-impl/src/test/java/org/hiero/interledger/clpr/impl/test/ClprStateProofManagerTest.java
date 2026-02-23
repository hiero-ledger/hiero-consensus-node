// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr.impl.test;

import static com.hedera.node.app.blocks.BlockStreamManager.HASH_OF_ZERO;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hedera.hapi.block.stream.MerklePath;
import com.hedera.hapi.block.stream.SiblingNode;
import com.hedera.hapi.block.stream.StateProof;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.node.app.spi.state.BlockProvenSnapshotProvider;
import com.hedera.node.app.state.BlockProvenStateAccessor;
import com.hedera.node.config.data.ClprConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.StateLifecycleManager;
import com.swirlds.state.lifecycle.StateDefinition;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.state.spi.CommittableWritableStates;
import com.swirlds.state.test.fixtures.merkle.VirtualMapStateTestUtils;
import java.util.List;
import java.util.Optional;
import org.assertj.core.api.Assertions;
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

    private static final Bytes CONSENSUS_HEADERS_SUBROOT =
            Bytes.fromBase64("VfWIxMsdxx8xeKKwBtgcV0YwwDF6OHsZrEU0lEUPc63PXxb6H1/X7l+VkTXC2YPT");
    private static final List<SiblingNode> SIBLING_HASHES = List.of(
            SiblingNode.newBuilder()
                    .isLeft(true)
                    .hash(Bytes.fromBase64("j8MEzU5/P58u3UzlwT+L3282wAfz82X67fujxEgZwmoQ9I9y9zBumyAStcvqtxXD"))
                    .build(),
            SiblingNode.newBuilder()
                    .isLeft(false)
                    .hash(Bytes.fromBase64("CFZyFyEOYlz1fqEc+HXUYSUt7YSChUHzv6utlcEPWOPdNjg++22l6ECH+U9+eaHy"))
                    .build(),
            SiblingNode.newBuilder()
                    .isLeft(true)
                    .hash(Bytes.fromBase64("8UYblZBFz3RuOqUJmjlaodmiBmDcpZgnTQItWxOBWYPhTmw3UOcn7RkKNhSG12ve"))
                    .build());
    public static final MerklePath STATE_SUBROOT_MERKLE_PATH = MerklePath.newBuilder()
            .hash(CONSENSUS_HEADERS_SUBROOT)
            .siblings(SIBLING_HASHES)
            .build();
    public static final Bytes TSS_SIGNATURE = Bytes.fromHex("AAAAAABBBBBBAAAAABBBBB");
    public static final Timestamp BLOCK_TIMESTAMP =
            Timestamp.newBuilder().seconds(1764665134).nanos(146615000).build();

    private ClprStateProofManager manager;
    private BlockProvenStateAccessor snapshotProvider;
    private VirtualMapState testState;
    private ClprConfig devModeConfig;

    @BeforeEach
    void setUp() {
        setupBase();
        testState = buildMerkleStateWithConfigurations(
                configurationMap,
                ClprLocalLedgerMetadata.newBuilder().ledgerId(localClprLedgerId).build());
        final var stateLifecycleManager = mock(StateLifecycleManager.class);
        when(stateLifecycleManager.getLatestImmutableState()).thenReturn(testState);
        snapshotProvider = mock(BlockProvenStateAccessor.class);
        given(snapshotProvider.latestSnapshot())
                .willReturn(Optional.of(new BlockProvenStateAccessor.BlockSignedSnapshot(
                        testState, TSS_SIGNATURE, BLOCK_TIMESTAMP, STATE_SUBROOT_MERKLE_PATH)));

        // Create dev mode config for testing
        devModeConfig = new ClprConfig(true, 5000, true, true);
        manager = new ClprStateProofManager(snapshotProvider, devModeConfig, MOCK_TSS_FACTORY);
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
    void getLocalLedgerIdReturnsNullWhenLedgerIdMissing() {
        final var emptyState = buildMerkleStateWithConfigurations(java.util.Map.of());
        final var emptyLifecycleManager = mock(StateLifecycleManager.class);
        when(emptyLifecycleManager.getLatestImmutableState()).thenReturn(emptyState);
        final var emptyAccessor = new BlockProvenStateAccessor(emptyLifecycleManager);
        emptyAccessor.registerBlockMetadata(
                HASH_OF_ZERO, HASH_OF_ZERO, TSS_SIGNATURE, BLOCK_TIMESTAMP, STATE_SUBROOT_MERKLE_PATH);
        final var managerWithMissingLedgerId =
                new ClprStateProofManager(emptyAccessor, devModeConfig, MOCK_TSS_FACTORY);
        assertNull(managerWithMissingLedgerId.getLocalLedgerId());
    }

    @Test
    void getLocalLedgerIdWorksInProductionMode() {
        final var prodConfig = new ClprConfig(true, devModeConfig.connectionFrequency(), true, false);
        final var prodManager = new ClprStateProofManager(snapshotProvider, prodConfig, MOCK_TSS_FACTORY);
        final var ledgerId = prodManager.getLocalLedgerId();
        assertNotNull(ledgerId);
        assertEquals(Bytes.wrap(rawLocalLedgerId), ledgerId.ledgerId());
    }

    @Test
    void getLocalLedgerIdReturnsNullInProductionModeWhenSnapshotUnavailable() {
        final var prodConfig = new ClprConfig(true, devModeConfig.connectionFrequency(), true, false);
        final BlockProvenSnapshotProvider emptyProvider = Optional::empty;
        final var prodManager = new ClprStateProofManager(emptyProvider, prodConfig, MOCK_TSS_FACTORY);
        assertNull(prodManager.getLocalLedgerId());
    }

    @Test
    void getLedgerConfigurationThrowsWhenBlockSignatureMetadataMissing() {
        final var someLedgerId = ClprLedgerId.newBuilder()
                .ledgerId(Bytes.fromHex("123456abcdef"))
                .build();

        // Test with empty TSS signature
        given(snapshotProvider.latestSnapshot())
                .willReturn(Optional.of(new BlockProvenStateAccessor.BlockSignedSnapshot(
                        testState, Bytes.EMPTY, BLOCK_TIMESTAMP, STATE_SUBROOT_MERKLE_PATH)));
        Assertions.assertThatThrownBy(() -> manager.getLedgerConfiguration(someLedgerId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TSS signature");

        // Test with default timestamp
        given(snapshotProvider.latestSnapshot())
                .willReturn(Optional.of(new BlockProvenStateAccessor.BlockSignedSnapshot(
                        testState, TSS_SIGNATURE, Timestamp.DEFAULT, STATE_SUBROOT_MERKLE_PATH)));
        Assertions.assertThatThrownBy(() -> manager.getLedgerConfiguration(someLedgerId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("timestamp");

        // Test with default MerklePath
        given(snapshotProvider.latestSnapshot())
                .willReturn(Optional.of(new BlockProvenStateAccessor.BlockSignedSnapshot(
                        testState, TSS_SIGNATURE, BLOCK_TIMESTAMP, MerklePath.DEFAULT)));
        Assertions.assertThatThrownBy(() -> manager.getLedgerConfiguration(someLedgerId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Merkle path");
    }

    @Test
    void getLocalLedgerIdUsesMetadataWhenPresent() {
        final var metadata =
                ClprLocalLedgerMetadata.newBuilder().ledgerId(localClprLedgerId).build();
        final var state =
                buildMerkleStateWithConfigurations(java.util.Map.of(remoteClprLedgerId, remoteClprConfig), metadata);
        final var lifecycleManager = mock(StateLifecycleManager.class);
        when(lifecycleManager.getLatestImmutableState()).thenReturn(state);
        final var managerWithMetadata = new ClprStateProofManager(snapshotProvider, devModeConfig, MOCK_TSS_FACTORY);

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
        final BlockProvenSnapshotProvider emptyProvider = Optional::empty;
        final var emptyManager = new ClprStateProofManager(emptyProvider, devModeConfig, MOCK_TSS_FACTORY);
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
        final var prodManager = new ClprStateProofManager(snapshotProvider, prodConfig, MOCK_TSS_FACTORY);
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
    void getLedgerConfigurationWorksWhenDevModeDisabled() {
        final var prodConfig = new ClprConfig(true, devModeConfig.connectionFrequency(), true, false);
        final var prodManager = new ClprStateProofManager(snapshotProvider, prodConfig, MOCK_TSS_FACTORY);
        final var proof = prodManager.getLedgerConfiguration(remoteClprLedgerId);
        assertNotNull(proof);
        assertEquals(remoteClprConfig, ClprStateProofUtils.extractConfiguration(proof));
    }

    @Test
    void validateStateProofWorksWhenDevModeDisabled() {
        final var prodConfig = new ClprConfig(true, devModeConfig.connectionFrequency(), true, false);
        final var prodManager = new ClprStateProofManager(snapshotProvider, prodConfig, MOCK_TSS_FACTORY);
        final var stateProof = buildLocalClprStateProofWrapper(remoteClprConfig);
        final var txn = validTransaction(stateProof);

        assertTrue(prodManager.validateStateProof(txn));
    }

    @Test
    void readAllLedgerConfigurationsReturnsAllConfigs() {
        final var allConfigs = manager.readAllLedgerConfigurations();
        assertNotNull(allConfigs);
        assertEquals(2, allConfigs.size());
        assertTrue(allConfigs.containsKey(localClprLedgerId));
        assertTrue(allConfigs.containsKey(remoteClprLedgerId));
        assertEquals(localClprConfig, allConfigs.get(localClprLedgerId));
        assertEquals(remoteClprConfig, allConfigs.get(remoteClprLedgerId));
    }

    @Test
    void readAllLedgerConfigurationsReturnsEmptyWhenSnapshotUnavailable() {
        final BlockProvenSnapshotProvider emptyProvider = Optional::empty;
        final var emptyManager = new ClprStateProofManager(emptyProvider, devModeConfig, MOCK_TSS_FACTORY);
        final var configs = emptyManager.readAllLedgerConfigurations();
        assertNotNull(configs);
        assertTrue(configs.isEmpty());
    }

    @Test
    void readAllLedgerConfigurationsReturnsEmptyWhenStateNotInitialized() {
        final var emptyState = VirtualMapStateTestUtils.createTestState();
        final var emptyLifecycleManager = mock(StateLifecycleManager.class);
        when(emptyLifecycleManager.getLatestImmutableState()).thenReturn(emptyState);
        final var emptyAccessor = new BlockProvenStateAccessor(emptyLifecycleManager);
        emptyAccessor.registerBlockMetadata(
                HASH_OF_ZERO, HASH_OF_ZERO, TSS_SIGNATURE, BLOCK_TIMESTAMP, STATE_SUBROOT_MERKLE_PATH);
        final var managerWithoutState = new ClprStateProofManager(emptyAccessor, devModeConfig, MOCK_TSS_FACTORY);

        final var configs = managerWithoutState.readAllLedgerConfigurations();
        assertNotNull(configs);
        assertTrue(configs.isEmpty());
    }

    @Test
    void readLedgerConfigurationReturnsConfigWhenFound() {
        final var config = manager.readLedgerConfiguration(remoteClprLedgerId);
        assertNotNull(config);
        assertEquals(remoteClprConfig, config);
    }

    @Test
    void readLedgerConfigurationReturnsNullWhenSnapshotUnavailable() {
        final BlockProvenSnapshotProvider emptyProvider = Optional::empty;
        final var emptyManager = new ClprStateProofManager(emptyProvider, devModeConfig, MOCK_TSS_FACTORY);
        assertNull(emptyManager.readLedgerConfiguration(remoteClprLedgerId));
    }

    @Test
    void readLedgerConfigurationReturnsNullWhenStateNotInitialized() {
        final var emptyState = VirtualMapStateTestUtils.createTestState();
        final var emptyLifecycleManager = mock(StateLifecycleManager.class);
        when(emptyLifecycleManager.getLatestImmutableState()).thenReturn(emptyState);
        final var emptyAccessor = new BlockProvenStateAccessor(emptyLifecycleManager);
        emptyAccessor.registerBlockMetadata(
                HASH_OF_ZERO, HASH_OF_ZERO, TSS_SIGNATURE, BLOCK_TIMESTAMP, STATE_SUBROOT_MERKLE_PATH);
        final var managerWithoutState = new ClprStateProofManager(emptyAccessor, devModeConfig, MOCK_TSS_FACTORY);

        assertNull(managerWithoutState.readLedgerConfiguration(remoteClprLedgerId));
    }

    @Test
    void readLedgerConfigurationReturnsNullForMissingLedgerId() {
        final var missingId = ClprLedgerId.newBuilder()
                .ledgerId(Bytes.fromHex("ab12cd34ef56"))
                .build();
        assertNull(manager.readLedgerConfiguration(missingId));
    }

    @Test
    void clprEnabledReflectsConfig() {
        assertTrue(manager.clprEnabled());
        final var disabledConfig = new ClprConfig(false, devModeConfig.connectionFrequency(), true, true);
        final var disabledManager = new ClprStateProofManager(snapshotProvider, disabledConfig, MOCK_TSS_FACTORY);
        assertFalse(disabledManager.clprEnabled());
    }

    @Test
    void getLedgerConfigurationReturnsNullForNonexistentLedgerId() {
        final var nonexistentId =
                ClprLedgerId.newBuilder().ledgerId(Bytes.fromHex("aabbccdd")).build();
        assertNull(manager.getLedgerConfiguration(nonexistentId));
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
}
