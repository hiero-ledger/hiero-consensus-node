// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.hedera;

import static java.util.Objects.requireNonNull;

import com.hedera.services.bdd.junit.hedera.utils.GrpcUtils;
import com.hedera.services.bdd.spec.infrastructure.HapiClients;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Query;
import com.hederahashgraph.api.proto.java.Response;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionResponse;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;

public abstract class AbstractGrpcNetwork extends AbstractNetwork implements HederaNetwork {
    /**
     * The clients for this network, if they are ready.
     */
    @Nullable
    protected HapiClients clients;

    protected AbstractGrpcNetwork(@NonNull final String networkName, @NonNull final List<HederaNode> nodes) {
        super(networkName, nodes);
    }

    protected AbstractGrpcNetwork(
            @NonNull final String networkName,
            @NonNull final List<HederaNode> nodes,
            @NonNull final HapiClients clients) {
        super(networkName, nodes);
        this.clients = requireNonNull(clients);
    }

    /**
     * {@inheritDoc}
     *
     * @throws NullPointerException if the clients are not ready
     */
    @Override
    public @NonNull Response send(
            @NonNull final Query query,
            @NonNull final HederaFunctionality functionality,
            @NonNull final com.hederahashgraph.api.proto.java.AccountID nodeAccountId,
            final boolean asNodeOperator) {
        requireNonNull(clients, "clients are not ready");
        requireNonNull(query);
        requireNonNull(functionality);
        requireNonNull(nodeAccountId);
        return GrpcUtils.send(query, clients, functionality, nodeAccountId, asNodeOperator);
    }

    /**
     * {@inheritDoc}
     *
     * @throws NullPointerException if the clients are not ready
     */
    @Override
    public TransactionResponse submit(
            @NonNull final Transaction transaction,
            @NonNull final HederaFunctionality functionality,
            @NonNull final SystemFunctionalityTarget target,
            @NonNull final AccountID nodeAccountId) {
        requireNonNull(clients, "clients are not ready");
        requireNonNull(transaction);
        requireNonNull(functionality);
        requireNonNull(nodeAccountId);
        return GrpcUtils.submit(transaction, clients, functionality, target, nodeAccountId);
    }
}
