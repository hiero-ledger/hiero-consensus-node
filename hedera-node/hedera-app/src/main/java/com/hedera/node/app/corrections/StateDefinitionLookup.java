// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.corrections;

import static java.util.Objects.requireNonNull;

import com.swirlds.state.lifecycle.StateDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Looks up the current definition of a state, and the service that owns it, by state id. Used to validate that
 * the changes in a {@code StateErrata} transaction target a known state, use the operation matching that state's
 * kind, and carry keys and values of that state's types.
 */
@FunctionalInterface
public interface StateDefinitionLookup {
    /**
     * A state definition together with the name of the service that owns the state.
     *
     * @param serviceName the name of the service that owns the state
     * @param definition the current definition of the state
     */
    record ServiceStateDefinition(
            @NonNull String serviceName, @NonNull StateDefinition<?, ?> definition) {
        public ServiceStateDefinition {
            requireNonNull(serviceName);
            requireNonNull(definition);
        }
    }

    /**
     * Returns the current definition of the state with the given id, or {@code null} if no registered service
     * currently defines such a state.
     *
     * @param stateId the state id
     * @return the definition and owning service, or {@code null} if unknown
     */
    @Nullable
    ServiceStateDefinition lookup(int stateId);
}
