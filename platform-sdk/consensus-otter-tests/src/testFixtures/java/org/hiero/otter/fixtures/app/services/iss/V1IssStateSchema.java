// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.app.services.iss;

import com.hedera.hapi.node.base.SemanticVersion;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import com.swirlds.state.spi.WritableSingletonState;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;
import org.hiero.otter.fixtures.app.model.IssState;

import static com.hedera.hapi.util.HapiUtils.SEMANTIC_VERSION_COMPARATOR;
import static org.hiero.otter.fixtures.app.state.OtterStateId.ISS_SINGLETON_STATE_ID;

/**
 * Genesis schema for the Consistency service
 */
public class V1IssStateSchema extends Schema<SemanticVersion> {

    private static final int STATE_ID = ISS_SINGLETON_STATE_ID.id();
    private static final String STATE_KEY = "ISS_STATE_KEY";

    /**
     * Create a new instance
     *
     * @param version the current software version
     */
    public V1IssStateSchema(@NonNull final SemanticVersion version) {
        super(version, SEMANTIC_VERSION_COMPARATOR);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    @SuppressWarnings("rawtypes")
    public Set<StateDefinition> statesToCreate() {
        return Set.of(StateDefinition.singleton(STATE_ID, STATE_KEY, IssState.PROTOBUF));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void migrate(@NonNull final MigrationContext ctx) {
        final WritableSingletonState<IssState> issState =
                ctx.newStates().getSingleton(STATE_ID);
        if (issState.get() == null) {
            issState.put(IssState.DEFAULT);
        }
    }
}
