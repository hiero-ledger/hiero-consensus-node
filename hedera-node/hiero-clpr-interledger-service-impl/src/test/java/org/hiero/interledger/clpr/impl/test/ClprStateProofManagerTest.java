// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr.impl.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.block.stream.StateProof;
import com.hedera.node.app.spi.state.BlockProvenSnapshotProvider;
import com.hedera.node.app.state.BlockProvenStateAccessor;
import com.hedera.node.config.data.ClprConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.test.fixtures.merkle.TestVirtualMapState;
import org.hiero.hapi.interledger.clpr.ClprSetLedgerConfigurationTransactionBody;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerConfiguration;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerId;
import org.hiero.interledger.clpr.ClprService;
import org.hiero.interledger.clpr.impl.ClprStateProofManager;
import org.hiero.interledger.clpr.impl.ClprStateProofUtils;
import org.hiero.interledger.clpr.impl.schemas.V0650ClprSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ClprStateProofManagerTest extends ClprTestBase {

    private ClprStateProofManager manager;
    private BlockProvenStateAccessor snapshotProvider;
    private TestVirtualMapState testState;
    private ClprConfig devModeConfig;

    @BeforeEach
    void setUp() {
        setupBase();
        testState = TestVirtualMapState.createInstanceWithVirtualMapLabel(ClprService.NAME);
        // Register CLPR service state metadata and seed the virtual map with ledger configurations
        testState.initializeState(ledgerConfigStateMetadata());
        final var writableStates = testState.getWritableStates(ClprService.NAME);
        final var writableConfigurations = writableStates.<ClprLedgerId, ClprLedgerConfiguration>get(
                V0650ClprSchema.CLPR_LEDGER_CONFIGURATIONS_STATE_ID);
        configurationMap.forEach(writableConfigurations::put);
        if (writableStates instanceof com.swirlds.state.spi.CommittableWritableStates committableStates) {
            committableStates.commit();
        }
        // Mark the state immutable so Merkle hashing and proof generation mirror runtime behaviour
        testState.copy();
        snapshotProvider = new BlockProvenStateAccessor();
        snapshotProvider.update(testState);
        // Create dev mode config for testing
        devModeConfig = new ClprConfig(5000, true, true);
        manager = new ClprStateProofManager(snapshotProvider, devModeConfig);
    }

    private com.swirlds.state.lifecycle.StateMetadata<ClprLedgerId, ClprLedgerConfiguration>
            ledgerConfigStateMetadata() {
        return new com.swirlds.state.lifecycle.StateMetadata<>(
                ClprService.NAME,
                com.swirlds.state.lifecycle.StateDefinition.onDisk(
                        V0650ClprSchema.CLPR_LEDGER_CONFIGURATIONS_STATE_ID,
                        V0650ClprSchema.CLPR_LEDGER_CONFIGURATIONS_STATE_KEY,
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
    void getLocalLedgerIdReturnsNullWhenLedgerIdMissing() {
        final var emptyState = TestVirtualMapState.createInstanceWithVirtualMapLabel("empty");
        final var accessor = new BlockProvenStateAccessor();
        accessor.update(emptyState);
        final var managerWithMissingLedgerId = new ClprStateProofManager(accessor, devModeConfig);
        assertNull(managerWithMissingLedgerId.getLocalLedgerId());
    }

    @Test
    void getLedgerConfigurationReturnsConfigWhenFound() {
        final var proof = manager.getLedgerConfiguration(remoteClprLedgerId);
        assertNotNull(proof);
        assertEquals(remoteClprConfig, ClprStateProofUtils.extractConfiguration(proof));
    }

    @Test
    void getLedgerConfigurationReturnsNullWhenStateUnavailable() {
        final BlockProvenSnapshotProvider emptyProvider = () -> java.util.Optional.empty();
        final var emptyManager = new ClprStateProofManager(emptyProvider, devModeConfig);
        assertNull(emptyManager.getLedgerConfiguration(remoteClprLedgerId));
    }

    @Test
    void isDevModeEnabledReflectsConfig() {
        assertTrue(manager.isDevModeEnabled());
        final var prodConfig = new ClprConfig(devModeConfig.connectionFrequency(), true, false);
        final var prodManager = new ClprStateProofManager(snapshotProvider, prodConfig);
        assertFalse(prodManager.isDevModeEnabled());
    }

    @Test
    void validateStateProofReturnsTrueForValidProof() {
        final var stateProof = buildStateProof(remoteClprConfig);
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
}
