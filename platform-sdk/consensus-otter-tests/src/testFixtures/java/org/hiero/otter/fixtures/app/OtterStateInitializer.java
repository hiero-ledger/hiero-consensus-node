// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.app;

import com.hedera.hapi.node.base.SemanticVersion;
import com.swirlds.state.lifecycle.StateDefinition;
import com.swirlds.state.lifecycle.StateMetadata;
import com.swirlds.state.merkle.singleton.SingletonNode;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Comparator;

public class OtterStateInitializer {

    public void initOtterAppState(@NonNull final OtterAppState state) {
        initConsistencyState(state);
    }

    private void initConsistencyState(@NonNull final OtterAppState state) {
        final var schema = new V1ConsistencyStateSchema(
                SemanticVersion.newBuilder().minor(1).build());
        schema.statesToCreate().stream()
                .sorted(Comparator.comparing(StateDefinition::stateKey))
                .forEach(def -> {
                    // the metadata associates the state definition with the service
                    final var stateMetadata = new StateMetadata<>(ConsistencyService.NAME, schema, def);
                    state.put(stateMetadata, () -> new SingletonNode<>(
                            stateMetadata.serviceName(),
                            stateMetadata.stateDefinition().stateKey(),
                            stateMetadata.singletonClassId(),
                            stateMetadata.stateDefinition().valueCodec(),
                            null)
                    );
                    // FUTURE WORK: Switch to this implementation when Mega Map supports test applications
//                    state.initializeState(stateMetadata);
                });
    }
}
