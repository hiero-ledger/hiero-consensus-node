// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.schemas;

import static com.hedera.node.app.history.schemas.V071HistorySchema.ACTIVE_PROOF_CONSTRUCTION_STATE_ID;
import static com.hedera.node.app.history.schemas.V071HistorySchema.LEDGER_ID_STATE_ID;
import static com.hedera.node.app.history.schemas.V071HistorySchema.NEXT_PROOF_CONSTRUCTION_STATE_ID;
import static com.hedera.node.app.history.schemas.V0730HistorySchema.WRAPS_PROVING_KEY_HASH_KEY;
import static com.hedera.node.app.history.schemas.V0730HistorySchema.WRAPS_PROVING_KEY_HASH_STATE_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hedera.hapi.node.state.history.HistoryProofConstruction;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.node.config.data.TssConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableStates;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class V0730HistorySchemaTest {

    private static final String HASH_HEX = "aa".repeat(48);

    @Mock
    private MigrationContext ctx;

    @Mock
    private Configuration configuration;

    @Mock
    private TssConfig tssConfig;

    @Mock
    private WritableStates writableStates;

    @Mock
    private WritableSingletonState<ProtoBytes> singletonState;

    @Mock
    private WritableSingletonState<ProtoBytes> ledgerIdState;

    @Mock
    private WritableSingletonState<HistoryProofConstruction> activeConstructionState;

    @Mock
    private WritableSingletonState<HistoryProofConstruction> nextConstructionState;

    private final V0730HistorySchema subject = new V0730HistorySchema();

    @Test
    void definesExpectedSingleton() {
        final var statesToCreate = subject.statesToCreate();

        assertEquals(1, statesToCreate.size());
        final var def = statesToCreate.iterator().next();
        assertEquals(WRAPS_PROVING_KEY_HASH_KEY, def.stateKey());
        assertEquals(WRAPS_PROVING_KEY_HASH_STATE_ID, def.stateId());
        assertTrue(def.singleton());
    }

    @Test
    void migrateInitializesDefaultAndPersistsConfiguredHash() {
        givenNonGenesisMigrate(null, HASH_HEX);

        subject.restart(ctx);

        verify(singletonState).put(ProtoBytes.DEFAULT);
        verify(singletonState)
                .put(ProtoBytes.newBuilder().value(Bytes.fromHex(HASH_HEX)).build());
    }

    @Test
    void migrateSkipsWriteWhenConfiguredHashIsBlank() {
        givenNonGenesisMigrate(null, "");

        subject.restart(ctx);

        verify(singletonState).put(ProtoBytes.DEFAULT);
        verify(singletonState, never())
                .put(ProtoBytes.newBuilder().value(Bytes.fromHex(HASH_HEX)).build());
    }

    @Test
    void migrateOverwritesWhenHashDiffers() {
        final var existingHash = "bb".repeat(48);
        givenNonGenesisMigrate(new ProtoBytes(Bytes.fromHex(existingHash)), HASH_HEX);

        subject.restart(ctx);

        verify(singletonState, never()).put(ProtoBytes.DEFAULT);
        verify(singletonState)
                .put(ProtoBytes.newBuilder().value(Bytes.fromHex(HASH_HEX)).build());
    }

    @Test
    void migrateInitializesHistorySingletonsOnEnabledNonGenesisRestart() {
        givenNonGenesisMigrate(null, "");
        given(tssConfig.historyEnabled()).willReturn(true);
        given(writableStates.<ProtoBytes>getSingleton(LEDGER_ID_STATE_ID)).willReturn(ledgerIdState);
        given(ledgerIdState.get()).willReturn(null);
        given(writableStates.<HistoryProofConstruction>getSingleton(ACTIVE_PROOF_CONSTRUCTION_STATE_ID))
                .willReturn(activeConstructionState);
        given(activeConstructionState.get()).willReturn(null);
        given(writableStates.<HistoryProofConstruction>getSingleton(NEXT_PROOF_CONSTRUCTION_STATE_ID))
                .willReturn(nextConstructionState);
        given(nextConstructionState.get()).willReturn(null);

        subject.restart(ctx);

        verify(ledgerIdState).put(ProtoBytes.DEFAULT);
        verify(activeConstructionState).put(HistoryProofConstruction.DEFAULT);
        verify(nextConstructionState).put(HistoryProofConstruction.DEFAULT);
    }

    @Test
    void migrateDoesNothingOnGenesis() {
        given(ctx.isGenesis()).willReturn(true);

        subject.restart(ctx);

        verifyNoInteractions(writableStates, singletonState);
    }

    private void givenNonGenesisMigrate(final ProtoBytes existingValue, final String configuredHash) {
        given(ctx.isGenesis()).willReturn(false);
        given(ctx.newStates()).willReturn(writableStates);
        given(writableStates.<ProtoBytes>getSingleton(WRAPS_PROVING_KEY_HASH_STATE_ID))
                .willReturn(singletonState);
        given(singletonState.get()).willReturn(existingValue);
        given(ctx.appConfig()).willReturn(configuration);
        given(configuration.getConfigData(TssConfig.class)).willReturn(tssConfig);
        given(tssConfig.wrapsProvingKeyHash()).willReturn(configuredHash);
    }
}
