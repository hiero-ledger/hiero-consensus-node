// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.schemas;

import static com.hedera.node.app.history.schemas.V071HistorySchema.ACTIVE_PROOF_CONSTRUCTION_STATE_ID;
import static com.hedera.node.app.history.schemas.V0730HistorySchema.WRAPS_PROVING_KEY_HASH_KEY;
import static com.hedera.node.app.history.schemas.V0730HistorySchema.WRAPS_PROVING_KEY_HASH_STATE_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hedera.hapi.node.state.history.HistoryProof;
import com.hedera.hapi.node.state.history.HistoryProofConstruction;
import com.hedera.node.app.history.HistoryService;
import com.hedera.node.config.data.TssConfig;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableStates;
import org.junit.jupiter.api.BeforeEach;
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
    private WritableSingletonState<HistoryProofConstruction> activeConstructionState;

    @Mock
    private HistoryService historyService;

    private V0730HistorySchema subject;

    @BeforeEach
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
    void restartInitializesLatestHistoryProofFromActiveConstruction() {
        final var targetProof = HistoryProof.newBuilder().build();
        final var activeConstruction =
                HistoryProofConstruction.newBuilder().targetProof(targetProof).build();
        given(ctx.isGenesis()).willReturn(false);
        given(ctx.appConfig()).willReturn(configuration);
        given(configuration.getConfigData(TssConfig.class)).willReturn(tssConfig);
        given(tssConfig.historyEnabled()).willReturn(true);
        given(ctx.newStates()).willReturn(writableStates);
        given(writableStates.<HistoryProofConstruction>getSingleton(ACTIVE_PROOF_CONSTRUCTION_STATE_ID))
                .willReturn(activeConstructionState);
        given(activeConstructionState.get()).willReturn(activeConstruction);

        subject.restart(ctx);

        verify(historyService).setLatestHistoryProof(targetProof);
    }

    @Test
    void restartDoesNothingOnGenesis() {
        given(ctx.isGenesis()).willReturn(true);

        subject.restart(ctx);

        verifyNoInteractions(writableStates, activeConstructionState, historyService);
    }
}
