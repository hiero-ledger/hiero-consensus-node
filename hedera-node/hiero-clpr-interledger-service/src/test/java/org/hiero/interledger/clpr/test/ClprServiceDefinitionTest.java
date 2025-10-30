// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr.test;

import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.Response;
import com.hedera.hapi.node.transaction.TransactionResponse;
import com.hedera.pbj.runtime.RpcMethodDefinition;
import org.assertj.core.api.Assertions;
import org.hiero.interledger.clpr.ClprServiceDefinition;
import org.junit.jupiter.api.Test;

class ClprServiceDefinitionTest {

    @Test
    void checkBasePath() {
        Assertions.assertThat(ClprServiceDefinition.INSTANCE.basePath())
                .isEqualTo("org.hiero.hapi.interledger.clpr.ClprService");
    }

    @Test
    void methodsDefined() {
        final var methods = ClprServiceDefinition.INSTANCE.methods();
        Assertions.assertThat(methods)
                .containsExactlyInAnyOrder(
                        new RpcMethodDefinition<>(
                                "setLedgerConfiguration", Transaction.class, TransactionResponse.class),
                        new RpcMethodDefinition<>("getLedgerConfiguration", Query.class, Response.class));
    }
}
