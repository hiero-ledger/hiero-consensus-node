// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.util;

import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.transaction.TransactionResponse;
import com.hedera.pbj.runtime.RpcMethodDefinition;
import com.hedera.pbj.runtime.RpcServiceDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/**
 * Transactions and queries for the Util Service.
 */
@SuppressWarnings("java:S6548")
public final class UtilServiceDefinition implements RpcServiceDefinition {
    public static final UtilServiceDefinition INSTANCE = new UtilServiceDefinition();

    private static final Set<RpcMethodDefinition<?, ?>> methods = Set.of(
            new RpcMethodDefinition<>("prng", Transaction.class, TransactionResponse.class),
            new RpcMethodDefinition<>("atomicBatch", Transaction.class, TransactionResponse.class));

    private UtilServiceDefinition() {
        // Forbid instantiation
    }

    @Override
    @NonNull
    public String basePath() {
        return "proto.UtilService";
    }

    @Override
    @NonNull
    public Set<RpcMethodDefinition<?, ?>> methods() {
        return methods;
    }
}
