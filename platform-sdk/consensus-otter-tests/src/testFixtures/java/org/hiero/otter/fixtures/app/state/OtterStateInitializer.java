// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.app.state;

import static org.hiero.otter.fixtures.app.OtterStateUtils.commitState;

import com.swirlds.state.lifecycle.StateDefinition;
import com.swirlds.state.lifecycle.StateMetadata;
import com.swirlds.state.merkle.VirtualMapStateImpl;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.hiero.otter.fixtures.app.OtterService;

/**
 * Utility class to initialize the state for the OtterApp.
 */
public class OtterStateInitializer {

    private OtterStateInitializer() {}

    /**
     * Initialize the state for the OtterApp.
     *
     * @param state the state to initialize
     * @param services the services to initialize
     */
    public static void initOtterAppState(
            @NonNull final VirtualMapStateImpl state, @NonNull final List<OtterService> services) {
        for (final OtterService service : services) {
            final OtterServiceStateSpecification specification = service.stateSpecification();
            for (final StateDefinition<?, ?> stateDefinition : specification.statesToCreate()) {
                // the metadata associates the state definition with the service
                final StateMetadata<?, ?> stateMetadata = new StateMetadata<>(service.name(), stateDefinition);
                state.initializeState(stateMetadata);
            }
        }
        commitState(state);
    }
}
