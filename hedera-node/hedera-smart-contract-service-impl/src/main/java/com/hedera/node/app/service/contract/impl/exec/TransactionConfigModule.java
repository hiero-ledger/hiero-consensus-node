// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.service.contract.impl.annotations.TransactionScope;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.config.data.AccountsConfig;
import com.hedera.node.config.data.ContractsConfig;
import com.hedera.node.config.data.EntitiesConfig;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.node.config.data.HooksConfig;
import com.hedera.node.config.data.LedgerConfig;
import com.hedera.node.config.data.StakingConfig;
import com.swirlds.config.api.Configuration;
import dagger.Module;
import dagger.Provides;
import edu.umd.cs.findbugs.annotations.NonNull;

@Module
public interface TransactionConfigModule {
    @Provides
    @TransactionScope
    static Configuration provideConfiguration(@NonNull final HandleContext context) {
        return requireNonNull(context).configuration();
    }

    @Provides
    @TransactionScope
    static ContractsConfig provideContractsConfig(@NonNull final Configuration configuration) {
        return requireNonNull(configuration).getConfigData(ContractsConfig.class);
    }

    @Provides
    @TransactionScope
    static HederaConfig provideHederaConfig(@NonNull final Configuration configuration) {
        return requireNonNull(configuration).getConfigData(HederaConfig.class);
    }

    @Provides
    @TransactionScope
    static LedgerConfig provideLedgerConfig(@NonNull final Configuration configuration) {
        return requireNonNull(configuration).getConfigData(LedgerConfig.class);
    }

    @Provides
    @TransactionScope
    static HooksConfig provideHooksConfig(@NonNull final Configuration configuration) {
        return requireNonNull(configuration).getConfigData(HooksConfig.class);
    }

    @Provides
    @TransactionScope
    static StakingConfig provideStakingConfig(@NonNull final Configuration configuration) {
        return requireNonNull(configuration).getConfigData(StakingConfig.class);
    }

    @Provides
    @TransactionScope
    static EntitiesConfig provideEntitiesConfig(@NonNull final Configuration configuration) {
        return requireNonNull(configuration).getConfigData(EntitiesConfig.class);
    }

    @Provides
    @TransactionScope
    static AccountsConfig provideAccountsConfig(@NonNull final Configuration configuration) {
        return requireNonNull(configuration).getConfigData(AccountsConfig.class);
    }
}
