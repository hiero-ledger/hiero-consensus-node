// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.schemas;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hedera.hapi.node.state.hints.HintsConstruction;
import com.hedera.hapi.node.state.hints.HintsScheme;
import com.hedera.node.app.hints.impl.HintsContext;
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
    private HintsContext signingContext;

    @Mock
    private MigrationContext ctx;

    @Mock
    private WritableStates writableStates;

    @Mock
    private WritableSingletonState<HintsConstruction> activeConstructionState;

    @Test
    void restartDoesNotInitializeMissingSingletons() {
        final var subject = new V073HintsSchema(signingContext);
        given(ctx.isGenesis()).willReturn(false);
        given(ctx.appConfig())
                .willReturn(HederaTestConfigBuilder.create()
                        .withValue("tss.hintsEnabled", "true")
                        .getOrCreateConfig());
        given(ctx.newStates()).willReturn(writableStates);
        given(writableStates.<HintsConstruction>getSingleton(V059HintsSchema.ACTIVE_HINTS_CONSTRUCTION_STATE_ID))
                .willReturn(activeConstructionState);
        given(activeConstructionState.get()).willReturn(null);

        subject.restart(ctx);

        verify(activeConstructionState, never()).put(HintsConstruction.DEFAULT);
        verifyNoInteractions(signingContext);
    }

    @Test
    void restartInitializesSigningContextFromExistingActiveConstruction() {
        final var subject = new V073HintsSchema(signingContext);
        final var activeConstruction = HintsConstruction.newBuilder()
                .constructionId(1L)
                .hintsScheme(HintsScheme.DEFAULT)
                .build();
        given(ctx.isGenesis()).willReturn(false);
        given(ctx.appConfig())
                .willReturn(HederaTestConfigBuilder.create()
                        .withValue("tss.hintsEnabled", "true")
                        .getOrCreateConfig());
        given(ctx.newStates()).willReturn(writableStates);
        given(writableStates.<HintsConstruction>getSingleton(V059HintsSchema.ACTIVE_HINTS_CONSTRUCTION_STATE_ID))
                .willReturn(activeConstructionState);
        given(activeConstructionState.get()).willReturn(activeConstruction);

        subject.restart(ctx);

        verify(signingContext).setConstruction(activeConstruction);
    }

    @Test
    void restartSkipsInitializationForGenesisOrDisabledHints() {
        final var subject = new V073HintsSchema(signingContext);
        given(ctx.isGenesis()).willReturn(true);

        subject.restart(ctx);

        verifyNoInteractions(writableStates, activeConstructionState, signingContext);
    }
}
