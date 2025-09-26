// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.schedule;

import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.Response;
import com.hedera.hapi.node.transaction.TransactionResponse;
import com.hedera.pbj.runtime.RpcMethodDefinition;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class ScheduleServiceDefinitionTest {

    @Test
    void checkBasePath() {
        Assertions.assertThat(ScheduleServiceDefinition.INSTANCE.basePath()).isEqualTo("proto.ScheduleService");
    }

    @Test
    void methodsDefined() {
        final var methods = ScheduleServiceDefinition.INSTANCE.methods();
        Assertions.assertThat(methods)
                .containsExactlyInAnyOrder(
                        new RpcMethodDefinition<>("createSchedule", Transaction.class, TransactionResponse.class),
                        new RpcMethodDefinition<>("signSchedule", Transaction.class, TransactionResponse.class),
                        new RpcMethodDefinition<>("deleteSchedule", Transaction.class, TransactionResponse.class),
                        new RpcMethodDefinition<>("getScheduleInfo", Query.class, Response.class));
    }
}
