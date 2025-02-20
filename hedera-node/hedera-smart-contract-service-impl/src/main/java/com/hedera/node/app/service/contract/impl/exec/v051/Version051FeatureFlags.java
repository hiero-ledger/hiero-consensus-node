// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.v051;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.service.contract.impl.exec.v050.Version050FeatureFlags;
import com.hedera.node.config.data.ContractsConfig;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class Version051FeatureFlags extends Version050FeatureFlags {
    @Inject
    public Version051FeatureFlags() {
        // Dagger2
    }

    @Override
    public boolean isHederaAccountServiceEnabled(@NonNull Configuration config) {
        requireNonNull(config);
        return config.getConfigData(ContractsConfig.class).systemContractAccountServiceEnabled();
    }

    @Override
    public boolean isAuthorizedRawMethodEnabled(@NonNull Configuration config) {
        requireNonNull(config);
        return config.getConfigData(ContractsConfig.class).systemContractAccountServiceIsAuthorizedRawEnabled();
    }
}
