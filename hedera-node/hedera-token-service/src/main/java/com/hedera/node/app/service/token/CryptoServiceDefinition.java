// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token;

import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.Response;
import com.hedera.hapi.node.transaction.TransactionResponse;
import com.hedera.pbj.runtime.RpcMethodDefinition;
import com.hedera.pbj.runtime.RpcServiceDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/**
 * Transactions and queries for the Crypto Service.
 */
@SuppressWarnings("java:S6548")
public final class CryptoServiceDefinition implements RpcServiceDefinition {
    /**
     * The singleton instance of this class.
     */
    public static final CryptoServiceDefinition INSTANCE = new CryptoServiceDefinition();

    private static final Set<RpcMethodDefinition<?, ?>> methods = Set.of(
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

    private CryptoServiceDefinition() {
        // Forbid instantiation
    }

    @Override
    @NonNull
    public String basePath() {
        return "proto.CryptoService";
    }

    @Override
    @NonNull
    public Set<RpcMethodDefinition<?, ?>> methods() {
        return methods;
    }
}
