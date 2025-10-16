// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.systemtask.schemas;

import static com.hedera.hapi.util.HapiUtils.SEMANTIC_VERSION_COMPARATOR;
import static com.swirlds.state.lifecycle.StateMetadata.computeLabel;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.systemtask.SystemTask;
import com.hedera.hapi.platform.state.StateKey;
import com.hedera.node.app.systemtask.SystemTaskService;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/**
 * Schema V0690: Adds the SYSTEM_TASK_QUEUE queue state for system tasks.
 */
public class V0690SystemTaskSchema extends Schema<SemanticVersion> {

    public static final String SYSTEM_TASK_QUEUE_KEY = "SYSTEM_TASK_QUEUE";
    public static final int SYSTEM_TASK_QUEUE_STATE_ID =
            StateKey.KeyOneOfType.SYSTEMTASKSERVICE_I_SYSTEM_TASK_QUEUE.protoOrdinal();
    public static final String SYSTEM_TASK_QUEUE_STATE_LABEL =
            computeLabel(SystemTaskService.NAME, SYSTEM_TASK_QUEUE_KEY);

    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().major(0).minor(69).patch(0).build();

    public V0690SystemTaskSchema() {
        super(VERSION, SEMANTIC_VERSION_COMPARATOR);
    }

    @SuppressWarnings("rawtypes")
    @Override
    public @NonNull Set<StateDefinition> statesToCreate() {
        return Set.of(StateDefinition.queue(SYSTEM_TASK_QUEUE_STATE_ID, SYSTEM_TASK_QUEUE_KEY, SystemTask.PROTOBUF));
    }
}

