// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.app.services.roster;

import static org.hiero.otter.fixtures.app.state.OtterServiceStateSpecification.statesOf;

import com.hedera.hapi.node.base.SemanticVersion;
import com.swirlds.state.lifecycle.StateDefinition;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;
import org.hiero.consensus.roster.schemas.V0540RosterBaseSchema;
import org.hiero.otter.fixtures.app.state.OtterServiceStateSpecification;

/**
 * This class defines the state specification for the Roster service. The states are taken from the production
 * schema.
 */
public class RosterStateSpecification implements OtterServiceStateSpecification {

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Set<StateDefinition<?, ?>> statesToCreate() {
        return statesOf(new V0540RosterBaseSchema());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setDefaultValues(@NonNull final WritableStates states, @NonNull final SemanticVersion version) {
        // Like in production, the roster states stay empty until the genesis roster is written in the first round
    }
}
