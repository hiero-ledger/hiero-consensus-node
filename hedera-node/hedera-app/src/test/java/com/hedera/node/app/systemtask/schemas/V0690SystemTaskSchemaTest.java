// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.systemtask.schemas;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.node.state.systemtask.KeyPropagation;
import com.hedera.hapi.node.state.systemtask.SystemTask;
import com.swirlds.state.lifecycle.StateDefinition;
import java.util.Comparator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class V0690SystemTaskSchemaTest {

    private final V0690SystemTaskSchema subject = new V0690SystemTaskSchema();

    @Test
    @DisplayName("verify states to create includes SYSTEM_TASK_QUEUE with SystemTask codec")
    void verifyStatesToCreate() {
        final var sorted = subject.statesToCreate().stream()
                .sorted(Comparator.comparing(StateDefinition::stateKey))
                .toList();
        final var def = sorted.getFirst();
        assertThat(def.stateKey()).isEqualTo(V0690SystemTaskSchema.SYSTEM_TASK_QUEUE_KEY);
        assertThat(def.keyCodec()).isNull();
        assertThat(def.valueCodec()).isEqualTo(SystemTask.PROTOBUF);
    }

    @Test
    @DisplayName("enqueue/dequeue round-trip of SystemTask works and FIFO")
    void queueFifoRoundTrip() {
        final var a = SystemTask.newBuilder()
                .keyPropagation(KeyPropagation.newBuilder().build())
                .build();
        final var b = SystemTask.newBuilder()
                .keyPropagation(KeyPropagation.newBuilder().build())
                .build();

        final var queueId = V0690SystemTaskSchema.SYSTEM_TASK_QUEUE_STATE_ID;
        final var label = V0690SystemTaskSchema.SYSTEM_TASK_QUEUE_STATE_LABEL;

        final var q = new com.swirlds.state.test.fixtures.ListWritableQueueState<SystemTask>(queueId, label, new java.util.LinkedList<>());
        // Enqueue
        q.add(a);
        q.add(b);
        // Dequeue FIFO
        assertThat(q.poll()).isEqualTo(a);
        assertThat(q.poll()).isEqualTo(b);
        assertThat(q.poll()).isNull();
    }
}

