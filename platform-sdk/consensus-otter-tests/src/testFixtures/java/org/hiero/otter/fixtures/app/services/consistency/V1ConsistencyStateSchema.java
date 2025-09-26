// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.app.services.consistency;

import static org.hiero.otter.fixtures.app.state.OtterStateId.CONSISTENCY_SINGLETON_STATE_ID;

import com.hedera.hapi.node.base.SemanticVersion;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;
import org.hiero.otter.fixtures.app.state.model.ConsistencyState;

/**
 * Genesis schema for the Consistency service
 */
public class V1ConsistencyStateSchema extends Schema {

    private static final int STATE_ID = CONSISTENCY_SINGLETON_STATE_ID.id();
    private static final String STATE_KEY = "CONSISTENCY_SINGLETON";

    /**
     * Create a new instance
     *
     * @param version the current software version
     */
    public V1ConsistencyStateSchema(@NonNull final SemanticVersion version) {
        super(version);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    @SuppressWarnings("rawtypes")
    public Set<StateDefinition> statesToCreate() {
        return Set.of(StateDefinition.singleton(STATE_ID, STATE_KEY, ConsistencyState.PROTOBUF));
    }
}
