// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract;

import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.Response;
import com.hedera.hapi.node.transaction.TransactionResponse;
import com.hedera.pbj.runtime.RpcMethodDefinition;
import com.hedera.pbj.runtime.RpcServiceDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/**
 * Transactions and queries for the Smart Contract Service
 */
@SuppressWarnings("java:S6548")
public final class SmartContractServiceDefinition implements RpcServiceDefinition {
    /**
     * Singleton instance of the Token Service.
     */
    public static final SmartContractServiceDefinition INSTANCE = new SmartContractServiceDefinition();

    private static final Set<RpcMethodDefinition<?, ?>> methods = Set.of(
            new RpcMethodDefinition<>("createContract", Transaction.class, TransactionResponse.class),
            new RpcMethodDefinition<>("updateContract", Transaction.class, TransactionResponse.class),
            new RpcMethodDefinition<>("contractCallMethod", Transaction.class, TransactionResponse.class),
            new RpcMethodDefinition<>("contractCallLocalMethod", Query.class, Response.class),
            new RpcMethodDefinition<>("deleteContract", Transaction.class, TransactionResponse.class),
            new RpcMethodDefinition<>("systemDelete", Transaction.class, TransactionResponse.class),
            new RpcMethodDefinition<>("systemUndelete", Transaction.class, TransactionResponse.class),
            new RpcMethodDefinition<>("callEthereum", Transaction.class, TransactionResponse.class),
            new RpcMethodDefinition<>("lambdaSStore", Transaction.class, TransactionResponse.class),
            new RpcMethodDefinition<>("ContractGetBytecode", Query.class, Response.class),
            new RpcMethodDefinition<>("getBySolidityID", Query.class, Response.class),
            new RpcMethodDefinition<>("getTxRecordByContractID", Query.class, Response.class),
            new RpcMethodDefinition<>("getContractInfo", Query.class, Response.class));

    private SmartContractServiceDefinition() {
        // Forbid instantiation
    }

    @Override
    @NonNull
    public String basePath() {
        return "proto.SmartContractService";
    }

    @Override
    @NonNull
    public Set<RpcMethodDefinition<?, ?>> methods() {
        return methods;
    }
}
