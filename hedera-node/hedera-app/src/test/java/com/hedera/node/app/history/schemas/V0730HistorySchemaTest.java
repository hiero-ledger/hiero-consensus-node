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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hedera.hapi.node.state.history.HistoryProofConstruction;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.node.app.history.HistoryService;
import com.hedera.node.config.data.TssConfig;
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

    @Mock
    private MigrationContext ctx;

    @Mock
    private Configuration configuration;

    @Mock
    private TssConfig tssConfig;

    @Mock
    private WritableStates writableStates;

    @Mock
    private WritableSingletonState<ProtoBytes> ledgerIdState;

    @Mock
    private WritableSingletonState<HistoryProofConstruction> activeConstructionState;

    @Mock
    private WritableSingletonState<HistoryProofConstruction> nextConstructionState;

    @Mock
    private HistoryService historyService;

    private V0730HistorySchema subject;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        subject = new V0730HistorySchema(historyService);
    }

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
    void restartDoesNothingWhenHistoryIsDisabled() {
        givenNonGenesisRestart();
        given(tssConfig.historyEnabled()).willReturn(false);

        subject.restart(ctx);

        verifyNoInteractions(writableStates, historyService);
    }

    @Test
    void migrateInitializesHistorySingletonsOnEnabledNonGenesisRestart() {
        givenNonGenesisRestart();
        given(tssConfig.historyEnabled()).willReturn(true);
        given(ctx.newStates()).willReturn(writableStates);
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
        verifyNoInteractions(historyService);
    }

    @Test
    void migrateInitializesLatestHistoryProofFromActiveConstruction() {
        givenNonGenesisRestart();
        given(tssConfig.historyEnabled()).willReturn(true);
        given(ctx.newStates()).willReturn(writableStates);
        final var targetProof =
                com.hedera.hapi.node.state.history.HistoryProof.newBuilder().build();
        final var activeConstruction =
                HistoryProofConstruction.newBuilder().targetProof(targetProof).build();
        given(writableStates.<ProtoBytes>getSingleton(LEDGER_ID_STATE_ID)).willReturn(ledgerIdState);
        given(ledgerIdState.get()).willReturn(ProtoBytes.DEFAULT);
        given(writableStates.<HistoryProofConstruction>getSingleton(ACTIVE_PROOF_CONSTRUCTION_STATE_ID))
                .willReturn(activeConstructionState);
        given(activeConstructionState.get()).willReturn(activeConstruction);
        given(writableStates.<HistoryProofConstruction>getSingleton(NEXT_PROOF_CONSTRUCTION_STATE_ID))
                .willReturn(nextConstructionState);
        given(nextConstructionState.get()).willReturn(HistoryProofConstruction.DEFAULT);

        subject.restart(ctx);

        verify(historyService).setLatestHistoryProof(targetProof);
    }

    @Test
    void migrateDoesNothingOnGenesis() {
        given(ctx.isGenesis()).willReturn(true);

        subject.restart(ctx);

        verifyNoInteractions(writableStates);
    }

    private void givenNonGenesisRestart() {
        given(ctx.isGenesis()).willReturn(false);
        given(ctx.appConfig()).willReturn(configuration);
        given(configuration.getConfigData(TssConfig.class)).willReturn(tssConfig);
    }
}
