// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.addressbook;

import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.transaction.TransactionResponse;
import com.hedera.pbj.runtime.RpcMethodDefinition;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class AddressBookServiceDefinitionTest {
    @Test
    void checkBasePath() {
        Assertions.assertThat(AddressBookServiceDefinition.INSTANCE.basePath()).isEqualTo("proto.AddressBookService");
    }

    @Test
    void methodsDefined() {
        final var methods = AddressBookServiceDefinition.INSTANCE.methods();
        Assertions.assertThat(methods)
                .containsExactlyInAnyOrder(
                        new RpcMethodDefinition<>("createNode", Transaction.class, TransactionResponse.class),
                        new RpcMethodDefinition<>("updateNode", Transaction.class, TransactionResponse.class),
                        new RpcMethodDefinition<>("deleteNode", Transaction.class, TransactionResponse.class));
    }
}
