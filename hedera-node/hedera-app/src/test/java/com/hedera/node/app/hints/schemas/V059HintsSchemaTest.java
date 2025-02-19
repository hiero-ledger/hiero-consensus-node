/*
 * Copyright (C) 2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hedera.node.app.hints.schemas;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hedera.hapi.node.state.hints.HintsConstruction;
import com.hedera.hapi.node.state.hints.HintsScheme;
import com.hedera.hapi.node.state.hints.PreprocessedKeys;
import com.hedera.node.app.hints.impl.HintsContext;
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
        subject = new V059HintsSchema(signingContext);
    }

    @Test
    void definesStatesWithExpectedKeys() {
        final var expectedStateNames = Set.of(
                V059HintsSchema.ACTIVE_HINT_CONSTRUCTION_KEY,
                V059HintsSchema.NEXT_HINT_CONSTRUCTION_KEY,
                V059HintsSchema.PREPROCESSING_VOTES_KEY,
                V059HintsSchema.HINTS_KEY_SETS_KEY);
        final var actualStateNames =
                subject.statesToCreate().stream().map(StateDefinition::stateKey).collect(Collectors.toSet());
        assertEquals(expectedStateNames, actualStateNames);
    }

    @Test
    void ensuresNonNullSingletonValues() {
        given(migrationContext.newStates()).willReturn(writableStates);
        given(writableStates.<HintsConstruction>getSingleton(V059HintsSchema.ACTIVE_HINT_CONSTRUCTION_KEY))
                .willReturn(activeConstructionState);
        given(writableStates.<HintsConstruction>getSingleton(V059HintsSchema.NEXT_HINT_CONSTRUCTION_KEY))
                .willReturn(nextConstructionState);

        subject.migrate(migrationContext);

        verify(activeConstructionState).put(HintsConstruction.DEFAULT);
        verify(nextConstructionState).put(HintsConstruction.DEFAULT);
    }

    @Test
    void restartSetsFinishedConstructionInContext() {
        given(migrationContext.newStates()).willReturn(writableStates);
        given(writableStates.<HintsConstruction>getSingleton(V059HintsSchema.ACTIVE_HINT_CONSTRUCTION_KEY))
                .willReturn(activeConstructionState);
        final var construction = HintsConstruction.newBuilder()
                .hintsScheme(new HintsScheme(PreprocessedKeys.DEFAULT, List.of()))
                .build();
        given(activeConstructionState.get()).willReturn(construction);

        subject.restart(migrationContext);

        verify(signingContext).setConstruction(construction);
    }

    @Test
    void restartDoesNotSetUnfinishedConstructionInContext() {
        given(migrationContext.newStates()).willReturn(writableStates);
        given(writableStates.<HintsConstruction>getSingleton(V059HintsSchema.ACTIVE_HINT_CONSTRUCTION_KEY))
                .willReturn(activeConstructionState);
        given(activeConstructionState.get()).willReturn(HintsConstruction.DEFAULT);

        subject.restart(migrationContext);

        verifyNoInteractions(signingContext);
    }
}
