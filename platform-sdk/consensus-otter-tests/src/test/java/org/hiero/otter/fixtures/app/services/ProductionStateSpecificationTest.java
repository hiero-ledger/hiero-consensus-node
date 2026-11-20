// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.app.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hedera.hapi.node.base.SemanticVersion;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import com.swirlds.state.spi.WritableStates;
import java.util.List;
import org.hiero.consensus.platformstate.V0540PlatformStateSchema;
import org.hiero.consensus.roster.schemas.V0540RosterBaseSchema;
import org.hiero.otter.fixtures.app.services.platform.PlatformStateSpecification;
import org.hiero.otter.fixtures.app.services.roster.RosterStateSpecification;
import org.junit.jupiter.api.Test;

/**
 * Verifies that the Otter services with a production equivalent use the production state definitions and, like
 * production, do not write anything to the genesis state.
 */
class ProductionStateSpecificationTest {

    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().major(1).build();

    @Test
    void platformStateSpecificationMatchesProduction() {
        final PlatformStateSpecification specification = new PlatformStateSpecification();

        final List<StateDefinition<?, ?>> expectedDefinitions = productionStates(new V0540PlatformStateSchema());
        assertThat(specification.statesToCreate()).containsExactlyInAnyOrderElementsOf(expectedDefinitions);

        final WritableStates states = mock(WritableStates.class);
        specification.setDefaultValues(states, VERSION);
        verifyNoInteractions(states);
    }

    @Test
    void rosterStateSpecificationMatchesProduction() {
        final RosterStateSpecification specification = new RosterStateSpecification();

        final List<StateDefinition<?, ?>> expectedDefinitions = productionStates(new V0540RosterBaseSchema());
        assertThat(specification.statesToCreate()).containsExactlyInAnyOrderElementsOf(expectedDefinitions);

        final WritableStates states = mock(WritableStates.class);
        specification.setDefaultValues(states, VERSION);
        verifyNoInteractions(states);
    }

    /**
     * Returns the state definitions of a production schema, converted from the raw type that {@link Schema} uses.
     */
    private static List<StateDefinition<?, ?>> productionStates(final Schema<?> schema) {
        return schema.statesToCreate().stream()
                .<StateDefinition<?, ?>>map(definition -> definition)
                .toList();
    }
}
