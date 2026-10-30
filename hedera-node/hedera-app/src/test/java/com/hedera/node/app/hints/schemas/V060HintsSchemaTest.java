// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.schemas;

import static com.hedera.node.app.hints.schemas.V060HintsSchema.CRS_STATE_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hedera.hapi.node.state.hints.CRSState;
import com.hedera.hapi.node.state.hints.HintsConstruction;
import com.hedera.node.app.hints.HintsLibrary;
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
        subject = new V060HintsSchema(signingContext);
    }

    @Test
    void definesStatesWithExpectedKeys() {
        final var expectedStateNames = Set.of(CRS_STATE_KEY, V060HintsSchema.CRS_PUBLICATIONS_KEY);
        final var actualStateNames =
                subject.statesToCreate().stream().map(StateDefinition::stateKey).collect(Collectors.toSet());
        assertEquals(expectedStateNames, actualStateNames);
    }

    @Test
    void leavesSigningContextInitializationToTheLatestSchema() {
        subject.restart(migrationContext);
        verifyNoInteractions(signingContext);
    }
}
