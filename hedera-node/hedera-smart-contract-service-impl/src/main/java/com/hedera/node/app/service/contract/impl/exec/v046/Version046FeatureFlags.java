// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.v046;

import com.hedera.node.app.service.contract.impl.exec.v034.Version034FeatureFlags;
import com.hedera.node.config.data.ContractsConfig;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class Version046FeatureFlags extends Version034FeatureFlags {
    @Inject
    public Version046FeatureFlags() {
        // Dagger2
    }

    @Override
    public boolean isAllowCallsToNonContractAccountsEnabled(
            @NonNull ContractsConfig config, @Nullable Long possiblyGrandFatheredEntityNum) {
        final var grandfathered = possiblyGrandFatheredEntityNum != null
                && config.evmNonExtantContractsFail().contains(possiblyGrandFatheredEntityNum);
        return config.evmAllowCallsToNonContractAccounts() && !grandfathered;
    }

    @Override
    public boolean isChargeGasOnPreEvmException(@NonNull Configuration config) {
        return config.getConfigData(ContractsConfig.class).chargeGasOnEvmHandleException();
    }
}
