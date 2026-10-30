// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.app.services.platform;

import static org.hiero.otter.fixtures.app.state.OtterServiceStateSpecification.statesOf;

import com.hedera.hapi.node.base.SemanticVersion;
import com.swirlds.state.lifecycle.StateDefinition;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;
import org.hiero.consensus.platformstate.V0540PlatformStateSchema;
import org.hiero.otter.fixtures.app.state.OtterServiceStateSpecification;

/**
 * This class defines the state specification for the Platform service. The states are taken from the production
 * schema.
 */
public class PlatformStateSpecification implements OtterServiceStateSpecification {

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Set<StateDefinition<?, ?>> statesToCreate() {
        return statesOf(new V0540PlatformStateSchema());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setDefaultValues(@NonNull final WritableStates states, @NonNull final SemanticVersion version) {
        // Like in production, the platform state stays empty until the platform handles the first round
    }
}
