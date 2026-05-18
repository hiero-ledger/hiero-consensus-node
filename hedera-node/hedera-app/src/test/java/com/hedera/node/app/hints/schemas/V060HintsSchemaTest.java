// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.schemas;

import static com.hedera.node.app.hints.schemas.V060HintsSchema.CRS_STATE_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.swirlds.state.lifecycle.StateDefinition;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class V060HintsSchemaTest {
    private final V060HintsSchema subject = new V060HintsSchema();

    @Test
    void definesStatesWithExpectedKeys() {
        final var expectedStateNames = Set.of(CRS_STATE_KEY, V060HintsSchema.CRS_PUBLICATIONS_KEY);
        final var actualStateNames =
                subject.statesToCreate().stream().map(StateDefinition::stateKey).collect(Collectors.toSet());
        assertEquals(expectedStateNames, actualStateNames);
    }
}
