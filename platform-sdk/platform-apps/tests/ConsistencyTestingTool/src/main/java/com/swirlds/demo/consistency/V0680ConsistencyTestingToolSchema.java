// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.consistency;

import static com.hedera.hapi.util.HapiUtils.SEMANTIC_VERSION_COMPARATOR;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.primitives.ProtoLong;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/**
 * Schema definition of the Consistency Testing Tool.
 * <p>
 * Defines the state structure including a state long value and rounds handled counter.
 */
public class V0680ConsistencyTestingToolSchema extends Schema<SemanticVersion> {

    public static final String CONSISTENCY_SERVICE_NAME = "ConsistencyTestingToolService";

    // State IDs 26 and 28 are used for PlatformState and Rosters respectively, so don't use them here.
    public static final int STATE_LONG_STATE_ID = 1;
    private static final String STATE_LONG_STATE_KEY = "STATE_LONG";

    public static final int ROUND_HANDLED_STATE_ID = 2;
    private static final String ROUND_HANDLED_STATE_KEY = "ROUND_HANDLED";

    /**
     * The version of the schema.
     */
    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().major(0).minor(68).patch(0).build();

    public V0680ConsistencyTestingToolSchema() {
        super(VERSION, SEMANTIC_VERSION_COMPARATOR);
    }

    @NonNull
    @Override
    public Set<StateDefinition> statesToCreate() {
        return Set.of(
                StateDefinition.singleton(STATE_LONG_STATE_ID, STATE_LONG_STATE_KEY, ProtoLong.PROTOBUF),
                StateDefinition.singleton(ROUND_HANDLED_STATE_ID, ROUND_HANDLED_STATE_KEY, ProtoLong.PROTOBUF));
    }
}
