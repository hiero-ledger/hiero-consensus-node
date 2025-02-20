// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.networkadmin;

import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.transaction.TransactionResponse;
import com.hedera.pbj.runtime.RpcMethodDefinition;
import com.hedera.pbj.runtime.RpcServiceDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/**
 * The request and responses for freeze service.
 */
@SuppressWarnings("java:S6548")
public final class FreezeServiceDefinition implements RpcServiceDefinition {
    /**
     * The global singleton FreezeServiceDefinition INSTANCE.
     */
    public static final FreezeServiceDefinition INSTANCE = new FreezeServiceDefinition();

    private static final Set<RpcMethodDefinition<?, ?>> methods =
            Set.of(new RpcMethodDefinition<>("freeze", Transaction.class, TransactionResponse.class));

    private FreezeServiceDefinition() {
        // Forbid instantiation
    }

    /**
     * Returns the the base path for the freeze service.
     *
     * @return the base path
     */
    @Override
    @NonNull
    public String basePath() {
        return "proto.FreezeService";
    }

    /**
     * Returns the set of rpc methods supported by the freeze service.
     *
     * @return the methods
     */
    @Override
    @NonNull
    public Set<RpcMethodDefinition<?, ?>> methods() {
        return methods;
    }
}
