// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr;

import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.RpcService;
import com.hedera.node.app.spi.ServiceFactory;
import com.hedera.pbj.runtime.RpcServiceDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * Implements the HAPI <a
 * href="https://github.com/hashgraph/hedera-protobufs/blob/main/interledger/clpr_service.proto">CLPR
 * Service</a>.
 */
public interface ClprService extends RpcService {
    /**
     * The name of the service.
     */
    String NAME = "ClprService";

    @NonNull
    @Override
    default String getServiceName() {
        return NAME;
    }

    @NonNull
    @Override
    default Set<RpcServiceDefinition> rpcDefinitions() {
        return Set.of(ClprServiceDefinition.INSTANCE);
    }

    /**
     * Returns the concrete implementation instance of the service.
     *
     * @return the implementation instance
     */
    @NonNull
    static ClprService getInstance() {
        return ServiceFactory.loadService(ClprService.class, ServiceLoader.load(ClprService.class));
    }

    /**
     * Builds a CLPR ledger configuration system transaction body from the supplied inputs.
     *
     * @param activeRoster the active roster to derive endpoints from
     * @param ledgerId the ledger id to include in the configuration
     * @param consensusTime the consensus time to stamp on the configuration
     * @param includeServiceEndpoint whether to include service endpoints in the configuration
     * @param endpointProvider resolves a service endpoint for a given node id when endpoints are included
     * @param nodeAccountIdProvider resolves the node account id for a given node id
     * @return the transaction body ready for dispatch
     */
    static @NonNull TransactionBody buildLedgerConfigurationUpdateTransactionBody(
            @NonNull final com.hedera.hapi.node.state.roster.Roster activeRoster,
            @NonNull final com.hedera.hapi.node.base.AccountID payerAccountId,
            @NonNull final com.hedera.pbj.runtime.io.buffer.Bytes ledgerId,
            @NonNull final java.time.Instant consensusTime,
            final boolean includeServiceEndpoint,
            @NonNull
                    final java.util.function.Function<Long, com.hedera.hapi.node.base.ServiceEndpoint> endpointProvider,
            @NonNull
                    final java.util.function.Function<Long, com.hedera.hapi.node.base.AccountID>
                            nodeAccountIdProvider) {
        final var endpoints = new java.util.ArrayList<org.hiero.hapi.interledger.state.clpr.ClprEndpoint>();
        // Sort roster entries by nodeId to guarantee deterministic endpoint ordering in the generated config.
        activeRoster.rosterEntries().stream()
                .sorted(java.util.Comparator.comparing(com.hedera.hapi.node.state.roster.RosterEntry::nodeId))
                .forEach(rosterEntry -> {
                    final var endpointBuilder = org.hiero.hapi.interledger.state.clpr.ClprEndpoint.newBuilder();
                    endpointBuilder.signingCertificate(rosterEntry.gossipCaCertificate());
                    final var nodeAccountId = nodeAccountIdProvider.apply(rosterEntry.nodeId());
                    if (nodeAccountId != null) {
                        endpointBuilder.nodeAccountId(nodeAccountId);
                    }
                    if (includeServiceEndpoint) {
                        final var serviceEndpoint = endpointProvider.apply(rosterEntry.nodeId());
                        if (serviceEndpoint != null) {
                            endpointBuilder.endpoint(serviceEndpoint);
                        }
                    }
                    endpoints.add(endpointBuilder.build());
                });

        final var ledgerIdProto = org.hiero.hapi.interledger.state.clpr.ClprLedgerId.newBuilder()
                .ledgerId(ledgerId)
                .build();
        final var config = org.hiero.hapi.interledger.state.clpr.ClprLedgerConfiguration.newBuilder()
                .ledgerId(ledgerIdProto)
                .timestamp(com.hedera.hapi.node.base.Timestamp.newBuilder()
                        .seconds(consensusTime.getEpochSecond())
                        .nanos(consensusTime.getNano())
                        .build())
                .endpoints(endpoints)
                .build();

        final var stateProof = ClprStateProofUtils.buildLocalClprStateProofWrapper(config);

        final var txnBody = org.hiero.hapi.interledger.clpr.ClprSetLedgerConfigurationTransactionBody.newBuilder()
                .ledgerConfigurationProof(stateProof)
                .build();

        final var txnId = com.hedera.hapi.node.base.TransactionID.newBuilder()
                .accountID(payerAccountId)
                .transactionValidStart(com.hedera.hapi.node.base.Timestamp.newBuilder()
                        .seconds(consensusTime.getEpochSecond())
                        .nanos(consensusTime.getNano())
                        .build())
                .build();
        return com.hedera.hapi.node.transaction.TransactionBody.newBuilder()
                .transactionID(txnId)
                .clprSetLedgerConfiguration(txnBody)
                .build();
    }
}
