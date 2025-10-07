// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.app.services.consistency;

import static com.hedera.hapi.util.HapiUtils.SEMANTIC_VERSION_COMPARATOR;
import static org.hiero.otter.fixtures.app.state.OtterStateId.CONSISTENCY_SINGLETON_STATE_ID;

import com.hedera.hapi.node.base.SemanticVersion;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import com.swirlds.state.spi.WritableSingletonState;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;
import org.hiero.otter.fixtures.app.model.ConsistencyState;

/**
 * Genesis schema for the Consistency service
 */
public class V1ConsistencyStateSchema extends Schema<SemanticVersion> {

    private static final int STATE_ID = CONSISTENCY_SINGLETON_STATE_ID.id();
    private static final String STATE_KEY = "CONSISTENCY_STATE_KEY";

    /**
     * Create a new instance
     *
     * @param version the current software version
     */
    public V1ConsistencyStateSchema(@NonNull final SemanticVersion version) {
        super(version, SEMANTIC_VERSION_COMPARATOR);
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

    /**
     * {@inheritDoc}
     */
    @Override
    public void migrate(@NonNull final MigrationContext ctx) {
        final WritableSingletonState<ConsistencyState> consistencyState =
                ctx.newStates().getSingleton(STATE_ID);
        if (consistencyState.get() == null) {
            consistencyState.put(ConsistencyState.DEFAULT);
        }
    }
}
