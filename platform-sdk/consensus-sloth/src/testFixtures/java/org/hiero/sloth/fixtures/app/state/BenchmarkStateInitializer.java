// SPDX-License-Identifier: Apache-2.0
package org.hiero.sloth.fixtures.app.state;

import static org.hiero.sloth.fixtures.app.SlothStateUtils.commitState;

import com.swirlds.state.lifecycle.StateDefinition;
import com.swirlds.state.lifecycle.StateMetadata;
import com.swirlds.state.merkle.VirtualMapState;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.hiero.sloth.fixtures.app.SlothService;

/**
 * Utility class to initialize the state for the OtterApp.
 */
public class BenchmarkStateInitializer {

    private BenchmarkStateInitializer() {}

    /**
     * Initialize the state for the OtterApp.
     *
     * @param state the state to initialize
     * @param services the services to initialize
     */
    public static void initOtterAppState(
            @NonNull final VirtualMapState state, @NonNull final List<SlothService> services) {
        for (final SlothService service : services) {
            final BenchmarkServiceStateSpecification specification = service.stateSpecification();
            for (final StateDefinition<?, ?> stateDefinition : specification.statesToCreate()) {
                // the metadata associates the state definition with the service
                final StateMetadata<?, ?> stateMetadata = new StateMetadata<>(service.name(), stateDefinition);
                state.initializeState(stateMetadata);
            }
        }
        commitState(state);
    }
}
