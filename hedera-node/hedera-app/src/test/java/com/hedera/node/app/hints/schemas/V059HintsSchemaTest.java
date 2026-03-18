// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.schemas;

import static org.junit.jupiter.api.Assertions.*;

import com.swirlds.state.lifecycle.StateDefinition;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class V059HintsSchemaTest {
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
}
