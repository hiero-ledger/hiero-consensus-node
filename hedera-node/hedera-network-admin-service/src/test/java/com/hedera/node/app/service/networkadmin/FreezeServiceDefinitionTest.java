// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.networkadmin;

import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.transaction.TransactionResponse;
import com.hedera.pbj.runtime.RpcMethodDefinition;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class FreezeServiceDefinitionTest {

    @Test
    void checkBasePath() {
        Assertions.assertThat(FreezeServiceDefinition.INSTANCE.basePath()).isEqualTo("proto.FreezeService");
    }

    @Test
    void methodsDefined() {
        final var methods = FreezeServiceDefinition.INSTANCE.methods();
        Assertions.assertThat(methods)
                .containsExactlyInAnyOrder(
                        new RpcMethodDefinition<>("freeze", Transaction.class, TransactionResponse.class));
    }
}
