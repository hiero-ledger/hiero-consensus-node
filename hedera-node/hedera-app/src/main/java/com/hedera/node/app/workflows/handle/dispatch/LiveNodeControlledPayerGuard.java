// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle.dispatch;

import static com.hedera.node.app.spi.workflows.HandleContext.DispatchMetadata.Type.INTERNAL_SYSTEM_TRANSACTION;
import static com.hedera.node.app.spi.workflows.HandleContext.TransactionCategory.NODE;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.node.app.workflows.handle.Dispatch;
import com.hedera.node.config.data.AccountsConfig;
import com.hedera.node.config.data.HederaConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * The live consensus node {@link NodeControlledPayerGuard}: a NODE-category dispatch is rejected unless its payer is
 * node-controlled. Gossiped transactions must use the creator node's own account. Only trusted internal system
 * dispatches may instead use the configured system admin account, for example for node fee payments.
 */
@Singleton
public class LiveNodeControlledPayerGuard implements NodeControlledPayerGuard {
    @Inject
    public LiveNodeControlledPayerGuard() {
        // Dagger
    }

    @Override
    public boolean rejectsForeignNodePayer(@NonNull final Dispatch dispatch) {
        return dispatch.txnCategory() == NODE && !payerIsNodeControlled(dispatch);
    }

    private boolean payerIsNodeControlled(@NonNull final Dispatch dispatch) {
        final var payerId = dispatch.payerId();
        if (payerId.equals(dispatch.creatorInfo().accountId())) {
            return true;
        }
        final var config = dispatch.config();
        final var hederaConfig = config.getConfigData(HederaConfig.class);
        final var accountsConfig = config.getConfigData(AccountsConfig.class);
        final var systemAdminId = AccountID.newBuilder()
                .shardNum(hederaConfig.shard())
                .realmNum(hederaConfig.realm())
                .accountNum(accountsConfig.systemAdmin())
                .build();
        // The payer ID alone cannot establish trust: a node can name the system admin in a gossiped transaction.
        // This marker is supplied by SystemTransactions, never decoded from transaction bytes.
        return payerId.equals(systemAdminId)
                && Boolean.TRUE.equals(dispatch.handleContext()
                        .dispatchMetadata()
                        .getMetadataIfPresent(INTERNAL_SYSTEM_TRANSACTION, Boolean.class));
    }
}
