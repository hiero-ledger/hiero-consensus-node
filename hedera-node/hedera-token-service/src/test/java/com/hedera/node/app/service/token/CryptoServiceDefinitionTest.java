// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token;

import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.Response;
import com.hedera.hapi.node.transaction.TransactionResponse;
import com.hedera.pbj.runtime.RpcMethodDefinition;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class CryptoServiceDefinitionTest {

    @Test
    void checkBasePath() {
        Assertions.assertThat(CryptoServiceDefinition.INSTANCE.basePath()).isEqualTo("proto.CryptoService");
    }

    @Test
    void methodsDefined() {
        final var methods = CryptoServiceDefinition.INSTANCE.methods();
        Assertions.assertThat(methods)
                .containsExactlyInAnyOrder(
                        new RpcMethodDefinition<>("createAccount", Transaction.class, TransactionResponse.class),
                        new RpcMethodDefinition<>("updateAccount", Transaction.class, TransactionResponse.class),
                        new RpcMethodDefinition<>("cryptoTransfer", Transaction.class, TransactionResponse.class),
                        new RpcMethodDefinition<>("cryptoDelete", Transaction.class, TransactionResponse.class),
                        new RpcMethodDefinition<>("approveAllowances", Transaction.class, TransactionResponse.class),
                        new RpcMethodDefinition<>("deleteAllowances", Transaction.class, TransactionResponse.class),
                        new RpcMethodDefinition<>("addLiveHash", Transaction.class, TransactionResponse.class),
                        new RpcMethodDefinition<>("deleteLiveHash", Transaction.class, TransactionResponse.class),
                        new RpcMethodDefinition<>("getLiveHash", Query.class, Response.class),
                        new RpcMethodDefinition<>("getAccountRecords", Query.class, Response.class),
                        new RpcMethodDefinition<>("cryptoGetBalance", Query.class, Response.class),
                        new RpcMethodDefinition<>("getAccountInfo", Query.class, Response.class),
                        new RpcMethodDefinition<>("getTransactionReceipts", Query.class, Response.class),
                        new RpcMethodDefinition<>("getFastTransactionRecord", Query.class, Response.class),
                        new RpcMethodDefinition<>("getTxRecordByTxID", Query.class, Response.class),
                        new RpcMethodDefinition<>("getStakersByAccountID", Query.class, Response.class));
    }
}
