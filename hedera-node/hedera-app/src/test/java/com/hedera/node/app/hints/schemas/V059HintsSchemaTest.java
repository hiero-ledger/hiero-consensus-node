// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.schemas;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.state.hints.HintsConstruction;
import com.hedera.node.app.hints.impl.HintsContext;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.lifecycle.StateDefinition;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableStates;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class V059HintsSchemaTest {

    @Mock
    private WritableStates writableStates;

    @Mock
    private WritableSingletonState<HintsConstruction> activeConstructionState;

    @Mock
    private WritableSingletonState<HintsConstruction> nextConstructionState;

    @Mock
    private HintsContext signingContext;

    @Mock
    private MigrationContext migrationContext;

    private V059HintsSchema subject;

    @BeforeEach
    void setUp() {
        subject = new V059HintsSchema();
    }

    @Test
    void definesStatesWithExpectedKeys() {
        final var expectedStateNames = Set.of(
                V059HintsSchema.ACTIVE_HINTS_CONSTRUCTION_KEY,
                V059HintsSchema.NEXT_HINTS_CONSTRUCTION_KEY,
                V059HintsSchema.PREPROCESSING_VOTES_KEY,
                V059HintsSchema.HINTS_KEY_SETS_KEY);
        final var actualStateNames =
                subject.statesToCreate().stream().map(StateDefinition::stateKey).collect(Collectors.toSet());
        assertEquals(expectedStateNames, actualStateNames);
    }

    @Test
    void ensuresNonNullSingletonValues() {
        given(migrationContext.newStates()).willReturn(writableStates);
        given(writableStates.<HintsConstruction>getSingleton(V059HintsSchema.ACTIVE_HINTS_CONSTRUCTION_STATE_ID))
                .willReturn(activeConstructionState);
        given(writableStates.<HintsConstruction>getSingleton(V059HintsSchema.NEXT_HINTS_CONSTRUCTION_STATE_ID))
                .willReturn(nextConstructionState);

        subject.migrate(migrationContext);

        verify(activeConstructionState).put(HintsConstruction.DEFAULT);
        verify(nextConstructionState).put(HintsConstruction.DEFAULT);
    }
}
