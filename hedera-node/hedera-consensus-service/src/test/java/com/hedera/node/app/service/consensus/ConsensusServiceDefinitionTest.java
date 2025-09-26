// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.consensus;

import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.Response;
import com.hedera.hapi.node.transaction.TransactionResponse;
import com.hedera.pbj.runtime.RpcMethodDefinition;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class ConsensusServiceDefinitionTest {

    @Test
    void checkBasePath() {
        Assertions.assertThat(ConsensusServiceDefinition.INSTANCE.basePath()).isEqualTo("proto.ConsensusService");
    }

    @Test
    void methodsDefined() {
        final var methods = ConsensusServiceDefinition.INSTANCE.methods();
        Assertions.assertThat(methods)
                .containsExactlyInAnyOrder(
                        new RpcMethodDefinition<>("createTopic", Transaction.class, TransactionResponse.class),
                        new RpcMethodDefinition<>("updateTopic", Transaction.class, TransactionResponse.class),
                        new RpcMethodDefinition<>("deleteTopic", Transaction.class, TransactionResponse.class),
                        new RpcMethodDefinition<>("getTopicInfo", Query.class, Response.class),
                        new RpcMethodDefinition<>("submitMessage", Transaction.class, TransactionResponse.class));
    }
}
