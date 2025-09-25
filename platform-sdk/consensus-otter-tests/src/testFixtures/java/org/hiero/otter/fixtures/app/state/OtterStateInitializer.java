// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.app.state;

import com.hedera.hapi.node.base.SemanticVersion;
import com.swirlds.state.lifecycle.StateDefinition;
import com.swirlds.state.lifecycle.StateMetadata;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.otter.fixtures.app.services.consistency.ConsistencyService;
import org.hiero.otter.fixtures.app.services.consistency.V1ConsistencyStateSchema;

public class OtterStateInitializer {

    public void initOtterAppState(@NonNull final OtterAppState state) {
        initConsistencyState(state);
    }

    private void initConsistencyState(@NonNull final OtterAppState state) {
        final V1ConsistencyStateSchema schema = new V1ConsistencyStateSchema(
                SemanticVersion.newBuilder().minor(1).build());

        final StateDefinition<Object, ConsistencyState> def = StateDefinition.singleton(
                ConsistencyService.STATE_ID,
                ConsistencyService.STATE_KEY,
                ConsistencyState.PROTOBUF);

        // the metadata associates the state definition with the service
        final StateMetadata<Object, ConsistencyState> stateMetadata = new StateMetadata<>(ConsistencyService.NAME,
                schema,
                def);
        state.initializeState(stateMetadata);
    }
}
