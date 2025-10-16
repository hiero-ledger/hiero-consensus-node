// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.systemtask;

import static com.hedera.node.app.systemtask.schemas.V0690SystemTaskSchema.SYSTEM_TASK_QUEUE_STATE_ID;
import static com.hedera.node.app.systemtask.schemas.V0690SystemTaskSchema.SYSTEM_TASK_QUEUE_STATE_LABEL;
import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.node.state.systemtask.KeyPropagation;
import com.hedera.hapi.node.state.systemtask.SystemTask;
import com.swirlds.state.test.fixtures.ListWritableQueueState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SystemTasksTest {

    @Test
    @DisplayName("API exposes FIFO semantics over underlying queue")
    void fifoThroughApi() {
        final var q = new ListWritableQueueState<SystemTask>(
                SYSTEM_TASK_QUEUE_STATE_ID, SYSTEM_TASK_QUEUE_STATE_LABEL, new java.util.LinkedList<>());
        final var api = new SystemTasksImpl(q);

        final var a = SystemTask.newBuilder().keyPropagation(KeyPropagation.newBuilder().build()).build();
        final var b = SystemTask.newBuilder().keyPropagation(KeyPropagation.newBuilder().build()).build();

        api.offer(a);
        api.offer(b);

        // Peek sees the head without removal
        assertThat(api.peek()).contains(a);
        assertThat(api.peek()).contains(a);

        // Poll removes in FIFO order
        assertThat(api.poll()).contains(a);
        assertThat(api.poll()).contains(b);
        assertThat(api.poll()).isEmpty();
    }
}

