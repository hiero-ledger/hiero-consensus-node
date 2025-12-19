// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr.impl.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.block.stream.MerklePath;
import com.hedera.hapi.block.stream.SiblingNode;
import com.hedera.hapi.block.stream.StateProof;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.node.app.spi.state.BlockProvenSnapshotProvider;
import com.hedera.node.app.state.BlockProvenStateAccessor;
import com.hedera.node.config.data.ClprConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.state.spi.CommittableWritableStates;
import com.swirlds.state.test.fixtures.merkle.VirtualMapStateTestUtils;
import java.util.List;
import java.util.Optional;
import org.assertj.core.api.Assertions;
import org.hiero.hapi.interledger.clpr.ClprSetLedgerConfigurationTransactionBody;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerConfiguration;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerId;
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
        testState = buildMerkleStateWithConfigurations(configurationMap);
        snapshotProvider = new BlockProvenStateAccessor();
        snapshotProvider.update(testState, TSS_SIGNATURE, BLOCK_TIMESTAMP, STATE_SUBROOT_MERKLE_PATH);
        // Create dev mode config for testing
        devModeConfig = new ClprConfig(true, 5000, true, true);
        manager = new ClprStateProofManager(snapshotProvider, devModeConfig);
    }

    private com.swirlds.state.lifecycle.StateMetadata<ClprLedgerId, ClprLedgerConfiguration>
            ledgerConfigStateMetadata() {
        return new com.swirlds.state.lifecycle.StateMetadata<>(
                ClprService.NAME,
                com.swirlds.state.lifecycle.StateDefinition.onDisk(
                        V0700ClprSchema.CLPR_LEDGER_CONFIGURATIONS_STATE_ID,
                        V0700ClprSchema.CLPR_LEDGER_CONFIGURATIONS_STATE_KEY,
                        ClprLedgerId.PROTOBUF,
                        ClprLedgerConfiguration.PROTOBUF,
                        50_000L));
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
        final var emptyAccessor = new BlockProvenStateAccessor();
        emptyAccessor.update(emptyState, TSS_SIGNATURE, BLOCK_TIMESTAMP, STATE_SUBROOT_MERKLE_PATH);
        final var managerWithMissingLedgerId = new ClprStateProofManager(emptyAccessor, devModeConfig);
        final var ledgerId = managerWithMissingLedgerId.getLocalLedgerId();
        assertNotNull(ledgerId);
        assertEquals(ClprLedgerId.DEFAULT, ledgerId);
    }

    @Test
    void getLedgerConfigurationThrowsWhenBlockSignatureMetadataMissing() {
        final var someLedgerId = ClprLedgerId.newBuilder()
                .ledgerId(Bytes.fromHex("123456abcdef"))
                .build();

        snapshotProvider.update(testState, Bytes.EMPTY, BLOCK_TIMESTAMP, STATE_SUBROOT_MERKLE_PATH);
        Assertions.assertThatThrownBy(() -> manager.getLedgerConfiguration(someLedgerId))
                .isInstanceOf(IllegalStateException.class);

        snapshotProvider.update(testState, TSS_SIGNATURE, Timestamp.DEFAULT, STATE_SUBROOT_MERKLE_PATH);
        Assertions.assertThatThrownBy(() -> manager.getLedgerConfiguration(someLedgerId))
                .isInstanceOf(IllegalStateException.class);

        snapshotProvider.update(testState, TSS_SIGNATURE, BLOCK_TIMESTAMP, MerklePath.DEFAULT);
        Assertions.assertThatThrownBy(() -> manager.getLedgerConfiguration(someLedgerId))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void getLedgerConfigurationReturnsConfigWhenFound() {
        final var proof = manager.getLedgerConfiguration(remoteClprLedgerId);
        assertNotNull(proof);
        assertEquals(remoteClprConfig, ClprStateProofUtils.extractConfiguration(proof));
    }

    @Test
    void getLedgerConfigurationReturnsNullWhenStateUnavailable() {
        final BlockProvenSnapshotProvider emptyProvider = Optional::empty;
        final var emptyManager = new ClprStateProofManager(emptyProvider, devModeConfig);
        assertNull(emptyManager.getLedgerConfiguration(remoteClprLedgerId));
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

    private ClprSetLedgerConfigurationTransactionBody validTransaction(final StateProof proof) {
        return ClprSetLedgerConfigurationTransactionBody.newBuilder()
                .ledgerConfigurationProof(proof)
                .build();
    }

    private VirtualMapState buildMerkleStateWithConfigurations(
            final java.util.Map<ClprLedgerId, ClprLedgerConfiguration> configurations) {
        final var state = VirtualMapStateTestUtils.createTestState();
        state.initializeState(ledgerConfigStateMetadata());
        final var writableStates = state.getWritableStates(ClprService.NAME);
        final var writableConfigurations = writableStates.<ClprLedgerId, ClprLedgerConfiguration>get(
                V0700ClprSchema.CLPR_LEDGER_CONFIGURATIONS_STATE_ID);
        configurations.forEach(writableConfigurations::put);
        if (writableStates instanceof CommittableWritableStates committableStates) {
            committableStates.commit();
        }
        // Make the state immutable and compute hashes so Merkle proofs can be generated
        state.copy();
        state.computeHash();
        return state;
    }
}
