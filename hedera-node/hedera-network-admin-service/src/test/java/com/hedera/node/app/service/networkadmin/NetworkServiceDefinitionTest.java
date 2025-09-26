// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.networkadmin;

import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.Response;
import com.hedera.hapi.node.transaction.TransactionResponse;
import com.hedera.pbj.runtime.RpcMethodDefinition;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class NetworkServiceDefinitionTest {

    @Test
    void checkBasePath() {
        Assertions.assertThat(NetworkServiceDefinition.INSTANCE.basePath()).isEqualTo("proto.NetworkService");
    }

    @Test
    void methodsDefined() {
        final var methods = NetworkServiceDefinition.INSTANCE.methods();
        Assertions.assertThat(methods)
                .containsExactlyInAnyOrder(
                        new RpcMethodDefinition<>("getVersionInfo", Query.class, Response.class),
                        new RpcMethodDefinition<>("getExecutionTime", Query.class, Response.class),
                        new RpcMethodDefinition<>("uncheckedSubmit", Transaction.class, TransactionResponse.class),
                        new RpcMethodDefinition<>("getAccountDetails", Query.class, Response.class));
    }
}
