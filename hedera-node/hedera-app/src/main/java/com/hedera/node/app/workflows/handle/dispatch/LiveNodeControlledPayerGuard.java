// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle.dispatch;

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
 * node-controlled. Only two payers are node-controlled and therefore legitimate: the configured system admin account
 * (used by synthetically dispatched system transactions such as node fee payments) and the creator node's own account
 * (used by gossiped node-submitted votes the node pays for itself).
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
        return payerId.equals(systemAdminId);
    }
}
