// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.schemas;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hedera.hapi.node.state.hints.HintsConstruction;
import com.hedera.node.app.hints.HintsLibrary;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableStates;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class V073HintsSchemaTest {
    @Mock
    private HintsLibrary library;

    @Mock
    private MigrationContext ctx;

    @Mock
    private WritableStates writableStates;

    @Mock
    private WritableSingletonState<HintsConstruction> activeConstructionState;

    @Mock
    private WritableSingletonState<HintsConstruction> nextConstructionState;

    @Test
    void restartInitializesMissingSingletonsWhenHintsEnabled() {
        final var subject = new V073HintsSchema(library);
        given(ctx.isGenesis()).willReturn(false);
        given(ctx.appConfig())
                .willReturn(HederaTestConfigBuilder.create()
                        .withValue("tss.hintsEnabled", "true")
                        .getOrCreateConfig());
        given(ctx.newStates()).willReturn(writableStates);
        given(writableStates.<HintsConstruction>getSingleton(V059HintsSchema.ACTIVE_HINTS_CONSTRUCTION_STATE_ID))
                .willReturn(activeConstructionState);
        given(activeConstructionState.get()).willReturn(null);
        given(writableStates.<HintsConstruction>getSingleton(V059HintsSchema.NEXT_HINTS_CONSTRUCTION_STATE_ID))
                .willReturn(nextConstructionState);
        given(nextConstructionState.get()).willReturn(null);

        subject.restart(ctx);

        verify(activeConstructionState).put(HintsConstruction.DEFAULT);
        verify(nextConstructionState).put(HintsConstruction.DEFAULT);
        verifyNoInteractions(library);
    }

    @Test
    void restartSkipsInitializationForGenesisOrDisabledHints() {
        final var subject = new V073HintsSchema(library);
        given(ctx.isGenesis()).willReturn(true);

        subject.restart(ctx);

        verifyNoInteractions(writableStates, activeConstructionState, nextConstructionState, library);
    }
}
