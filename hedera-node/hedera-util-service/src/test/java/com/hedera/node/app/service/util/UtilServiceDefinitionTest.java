// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.util;

import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.transaction.TransactionResponse;
import com.hedera.pbj.runtime.RpcMethodDefinition;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class UtilServiceDefinitionTest {

    @Test
    void checkBasePath() {
        Assertions.assertThat(UtilServiceDefinition.INSTANCE.basePath()).isEqualTo("proto.UtilService");
    }

    @Test
    void methodsDefined() {
        final var methods = UtilServiceDefinition.INSTANCE.methods();
        Assertions.assertThat(methods)
                .containsExactlyInAnyOrder(
                        new RpcMethodDefinition<>("prng", Transaction.class, TransactionResponse.class),
                        new RpcMethodDefinition<>("atomicBatch", Transaction.class, TransactionResponse.class));
    }

    @Test
    void instanceCantLoadWithoutImplementation() {
        Assertions.assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(UtilService::getInstance)
                .isNotNull();
    }
}
