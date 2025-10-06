// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.app.state;

import com.hedera.hapi.node.base.SemanticVersion;
import com.swirlds.state.lifecycle.StateDefinition;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/**
 * A specification for the state required by an Otter service.
 */
public interface OtterServiceStateSpecification {

    /**
     * The set of states to create for this service.
     *
     * @return the set of state definitions
     */
    @NonNull
    Set<StateDefinition<?, ?>> statesToCreate();

    /**
     * Initialize the state for this service.
     *
     * @param states the writable states to initialize
     * @param version the current software version
     */
    void initializeState(@NonNull WritableStates states, @NonNull SemanticVersion version);
}
