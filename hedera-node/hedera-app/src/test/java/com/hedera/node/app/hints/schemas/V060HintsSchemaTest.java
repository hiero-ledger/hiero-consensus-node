// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.schemas;

import static com.hedera.node.app.fixtures.AppTestBase.DEFAULT_CONFIG;
import static com.hedera.node.app.hints.schemas.V060HintsSchema.CRS_STATE_KEY;
import static com.hedera.node.app.hints.schemas.V060HintsSchema.CRS_STATE_STATE_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hedera.hapi.node.state.hints.CRSState;
import com.hedera.hapi.node.state.hints.HintsConstruction;
import com.hedera.hapi.node.state.hints.HintsScheme;
import com.hedera.hapi.node.state.hints.PreprocessedKeys;
import com.hedera.node.app.hints.HintsLibrary;
import com.hedera.node.app.hints.impl.HintsContext;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.lifecycle.StateDefinition;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableStates;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class V060HintsSchemaTest {

    @Mock
    private WritableStates writableStates;

    @Mock
    private WritableSingletonState<CRSState> crsState;

    @Mock
    private WritableSingletonState<HintsConstruction> activeConstructionState;

    @Mock
    private WritableSingletonState<HintsConstruction> nextConstructionState;

    @Mock
    private HintsContext signingContext;

    @Mock
    private MigrationContext migrationContext;

    @Mock
    private HintsLibrary library;

    private V060HintsSchema subject;

    @BeforeEach
    void setUp() {
        subject = new V060HintsSchema(signingContext, library);
    }

    @Test
    void definesStatesWithExpectedKeys() {
        final var expectedStateNames = Set.of(CRS_STATE_KEY, V060HintsSchema.CRS_PUBLICATIONS_KEY);
        final var actualStateNames =
                subject.statesToCreate().stream().map(StateDefinition::stateKey).collect(Collectors.toSet());
        assertEquals(expectedStateNames, actualStateNames);
    }

    @Test
    void restartSetsFinishedConstructionInContext() {
        given(migrationContext.appConfig())
                .willReturn(HederaTestConfigBuilder.create()
                        .withValue("tss.hintsEnabled", "true")
                        .getOrCreateConfig());
        given(migrationContext.newStates()).willReturn(writableStates);
        given(writableStates.<HintsConstruction>getSingleton(V059HintsSchema.ACTIVE_HINTS_CONSTRUCTION_STATE_ID))
                .willReturn(activeConstructionState);
        given(activeConstructionState.get()).willReturn(HintsConstruction.DEFAULT);
        given(writableStates.<HintsConstruction>getSingleton(V059HintsSchema.NEXT_HINTS_CONSTRUCTION_STATE_ID))
                .willReturn(nextConstructionState);
        given(writableStates.<CRSState>getSingleton(CRS_STATE_STATE_ID)).willReturn(crsState);
        final var construction = HintsConstruction.newBuilder()
                .hintsScheme(new HintsScheme(PreprocessedKeys.DEFAULT, List.of()))
                .build();
        given(activeConstructionState.get()).willReturn(construction);
        given(crsState.get()).willReturn(CRSState.DEFAULT);
        given(nextConstructionState.get()).willReturn(HintsConstruction.DEFAULT);

        subject.restart(migrationContext);

        verify(signingContext).setConstruction(construction);
    }

    @Test
    void restartDoesNotSetUnfinishedConstructionInContext() {
        given(migrationContext.appConfig()).willReturn(DEFAULT_CONFIG);
        given(migrationContext.newStates()).willReturn(writableStates);
        given(writableStates.<HintsConstruction>getSingleton(V059HintsSchema.ACTIVE_HINTS_CONSTRUCTION_STATE_ID))
                .willReturn(activeConstructionState);
        given(activeConstructionState.get()).willReturn(HintsConstruction.DEFAULT);
        given(writableStates.<HintsConstruction>getSingleton(V059HintsSchema.NEXT_HINTS_CONSTRUCTION_STATE_ID))
                .willReturn(nextConstructionState);
        given(writableStates.<CRSState>getSingleton(CRS_STATE_STATE_ID)).willReturn(crsState);

        subject.restart(migrationContext);

        verifyNoInteractions(signingContext);
    }
}
