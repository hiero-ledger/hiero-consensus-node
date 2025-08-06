// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.app;

import com.hedera.hapi.node.base.SemanticVersion;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;
import org.hiero.otter.fixtures.app.state.ConsistencyState;
import org.jetbrains.annotations.NotNull;

public class V1ConsistencyStateSchema extends Schema {

    /** Defines the section of the state that is for the Consistency Service */
    public static final String CONSISTENCY_SINGLETON_KEY = "CONSISTENCY_SINGLETON";

    /**
     * Create a new instance
     *
     * @param version The version of this schema
     */
    protected V1ConsistencyStateSchema(@NotNull final SemanticVersion version) {
        super(version);
    }

    @NonNull
    @Override
    public Set<StateDefinition> statesToCreate() {
        return Set.of(StateDefinition.singleton(CONSISTENCY_SINGLETON_KEY, ConsistencyState.PROTOBUF));
    }
}
