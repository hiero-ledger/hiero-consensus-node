// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.app.state;

import com.hedera.hapi.node.base.SemanticVersion;
import com.swirlds.state.lifecycle.StateDefinition;
import com.swirlds.state.lifecycle.StateMetadata;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.hiero.otter.fixtures.app.OtterAppState;
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
     * @param version the software version to set in the state
     * @param services the services to initialize
     */
    public static void initOtterAppState(
            @NonNull final OtterAppState state,
            @NonNull final SemanticVersion version,
            @NonNull final List<OtterService> services) {
        for (final OtterService service : services) {
            final OtterServiceStateSpecification specification = service.stateSpecification();
            for (final StateDefinition<?, ?> stateDefinition : specification.statesToCreate()) {
                // the metadata associates the state definition with the service
                final StateMetadata<?, ?> stateMetadata = new StateMetadata<>(service.name(), stateDefinition);
                state.initializeState(stateMetadata);
            }

            // set up the state's default values for this service
            specification.setDefaultValues(state.getWritableStates(service.name()), version);
        }
        state.commitState();
    }
}
