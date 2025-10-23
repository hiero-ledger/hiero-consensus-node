// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr;

import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.Response;
import com.hedera.hapi.node.transaction.TransactionResponse;
import com.hedera.pbj.runtime.RpcMethodDefinition;
import com.hedera.pbj.runtime.RpcServiceDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/**
 * Transactions and queries for the interledger CLPR Service.
 */
public final class ClprServiceDefinition implements RpcServiceDefinition {
    /**
     * The singleton instance of this class.
     */
    public static final ClprServiceDefinition INSTANCE = new ClprServiceDefinition();

    private static final Set<RpcMethodDefinition<?, ?>> methods = Set.of(
            new RpcMethodDefinition<>("setLedgerConfiguration", Transaction.class, TransactionResponse.class),
            new RpcMethodDefinition<>("getLedgerConfiguration", Query.class, Response.class));

    private ClprServiceDefinition() {
        // Forbid instantiation
    }

    @Override
    @NonNull
    public String basePath() {
        return "org.hiero.hapi.interledger.clpr.ClprService";
    }

    @Override
    @NonNull
    public Set<RpcMethodDefinition<?, ?>> methods() {
        return methods;
    }
}
