// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr;

import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.Response;
import com.hedera.hapi.node.transaction.TransactionResponse;
import com.hedera.pbj.runtime.RpcMethodDefinition;
import com.hedera.pbj.runtime.RpcServiceDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/**
 * Defines the CLPR transaction-accepting gRPC service. This service handles standard HAPI
 * transactions for cross-ledger protocol operations such as registering channels,
 * submitting bundles, and managing connectors.
 *
 * <p>Each method accepts a {@link Transaction} and returns a {@link TransactionResponse},
 * following the same pattern as other HAPI services like {@code ConsensusService} and
 * {@code TokenService}.
 */
@SuppressWarnings("java:S6548")
public final class ClprTransactionServiceDefinition implements RpcServiceDefinition {
    /** The singleton instance of this class. */
    public static final ClprTransactionServiceDefinition INSTANCE = new ClprTransactionServiceDefinition();

    private static final Set<RpcMethodDefinition<?, ?>> methods = Set.of(
            new RpcMethodDefinition<>("registerChannel", Transaction.class, TransactionResponse.class),
            new RpcMethodDefinition<>("completeChannel", Transaction.class, TransactionResponse.class),
            new RpcMethodDefinition<>("closeChannel", Transaction.class, TransactionResponse.class),
            new RpcMethodDefinition<>("submitBundle", Transaction.class, TransactionResponse.class),
            new RpcMethodDefinition<>("redactMessage", Transaction.class, TransactionResponse.class),
            new RpcMethodDefinition<>("registerConnector", Transaction.class, TransactionResponse.class),
            new RpcMethodDefinition<>("completeConnector", Transaction.class, TransactionResponse.class),
            new RpcMethodDefinition<>("deregisterConnector", Transaction.class, TransactionResponse.class),
            new RpcMethodDefinition<>("updateLedgerConfiguration", Transaction.class, TransactionResponse.class),
            new RpcMethodDefinition<>("getLedgerConfiguration", Query.class, Response.class),
            new RpcMethodDefinition<>("getEndpointManifest", Query.class, Response.class));

    private ClprTransactionServiceDefinition() {
        // Forbid instantiation
    }

    @Override
    @NonNull
    public String basePath() {
        return "proto.ClprService";
    }

    @Override
    @NonNull
    public Set<RpcMethodDefinition<?, ?>> methods() {
        return methods;
    }
}
