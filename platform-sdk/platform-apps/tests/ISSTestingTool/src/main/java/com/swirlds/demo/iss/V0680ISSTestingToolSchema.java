// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.iss;

import static com.hedera.hapi.util.HapiUtils.SEMANTIC_VERSION_COMPARATOR;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.primitives.ProtoLong;
import com.hedera.hapi.node.state.primitives.ProtoString;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/**
 * Schema definition of the ISS Testing Tool.
 * <p>
 * Defines the state structure including running sum, genesis timestamp,
 * and queues for planned ISS incidents and log errors.
 */
public class V0680ISSTestingToolSchema extends Schema<SemanticVersion> {

    public static final String ISS_SERVICE_NAME = "ISSTestingToolService";

    // State IDs 26 and 28 are used for PlatformState and Rosters respectively, so don't use them here.
    public static final int RUNNING_SUM_STATE_ID = 1;
    private static final String RUNNING_SUM_STATE_KEY = "RUNNING_SUM";

    public static final int GENESIS_TIMESTAMP_STATE_ID = 2;
    private static final String GENESIS_TIMESTAMP_STATE_KEY = "GENESIS_TIMESTAMP";

    public static final int PLANNED_ISS_LIST_STATE_ID = 3;
    private static final String PLANNED_ISS_LIST_STATE_KEY = "PLANNED_ISS_LIST";

    public static final int PLANNED_LOG_ERROR_LIST_STATE_ID = 4;
    private static final String PLANNED_LOG_ERROR_LIST_STATE_KEY = "PLANNED_LOG_ERROR_LIST";

    /**
     * The version of the schema.
     */
    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().major(0).minor(68).patch(0).build();

    public V0680ISSTestingToolSchema() {
        super(VERSION, SEMANTIC_VERSION_COMPARATOR);
    }

    @NonNull
    @Override
    public Set<StateDefinition> statesToCreate() {
        return Set.of(
                StateDefinition.singleton(RUNNING_SUM_STATE_ID, RUNNING_SUM_STATE_KEY, ProtoLong.PROTOBUF),
                StateDefinition.singleton(
                        GENESIS_TIMESTAMP_STATE_ID, GENESIS_TIMESTAMP_STATE_KEY, ProtoString.PROTOBUF),
                StateDefinition.queue(PLANNED_ISS_LIST_STATE_ID, PLANNED_ISS_LIST_STATE_KEY, PlannedIssCodec.INSTANCE),
                StateDefinition.queue(
                        PLANNED_LOG_ERROR_LIST_STATE_ID,
                        PLANNED_LOG_ERROR_LIST_STATE_KEY,
                        PlannedLogErrorCodec.INSTANCE));
    }
}
