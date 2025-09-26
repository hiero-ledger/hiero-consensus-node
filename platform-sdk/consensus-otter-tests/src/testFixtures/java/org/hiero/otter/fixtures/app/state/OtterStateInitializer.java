// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.app.state;

import com.hedera.hapi.node.base.SemanticVersion;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import com.swirlds.state.lifecycle.StateMetadata;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Comparator;
import java.util.List;
import org.hiero.otter.fixtures.app.OtterAppState;
import org.hiero.otter.fixtures.app.services.OtterService;

/**
 * Utility class to initialize the state for the OtterApp.
 */
public class OtterStateInitializer {

    private OtterStateInitializer() {}

    /**
     * Initialize the state for the OtterApp.
     *
     * @param configuration the configuration to use
     * @param state the state to initialize
     * @param version the software version to set in the state
     * @param services the services to initialize
     */
    @SuppressWarnings("rawtypes")
    public static void initOtterAppState(
            @NonNull final Configuration configuration,
            @NonNull final OtterAppState state,
            @NonNull final SemanticVersion version,
            @NonNull final List<OtterService> services) {
        for (final OtterService service : services) {
            final Schema schema = service.genesisSchema(version);
            final List<StateDefinition> stateDefinitions = schema.statesToCreate().stream()
                    .sorted(Comparator.comparing(StateDefinition::stateId)).toList();
            for (final StateDefinition<?, ?> stateDefinition : stateDefinitions) {

                // the metadata associates the state definition with the service
                final StateMetadata<?, ?> stateMetadata = new StateMetadata<>(service.name(), schema, stateDefinition);
                state.initializeState(stateMetadata);

                // perform the migration to create the initial state
                final MigrationContext migrationContext =
                        new GenesisMigrationContext(configuration, state, service.name());
                schema.migrate(migrationContext);
            }
        }
        state.commitState();
    }
}
