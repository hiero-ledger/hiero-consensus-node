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
     * Initialize the default values for the states managed by this service. This is called when the state is
     * first created during genesis or a restart and can be used to set up any necessary initial state.
     * The most common use case is to set up singleton states with non-null default values.
     *
     * @param states the writable states to initialize
     * @param version the current software version
     */
    void setDefaultValues(@NonNull WritableStates states, @NonNull SemanticVersion version);
}
