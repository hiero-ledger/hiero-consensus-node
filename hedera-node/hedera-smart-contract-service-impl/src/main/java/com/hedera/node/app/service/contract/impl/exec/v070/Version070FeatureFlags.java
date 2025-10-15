// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.v070;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.service.contract.impl.exec.v065.Version065FeatureFlags;
import com.hedera.node.config.data.ContractsConfig;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class Version070FeatureFlags extends Version065FeatureFlags {
    @Inject
    public Version070FeatureFlags() {
        // Dagger2
    }

    @Override
    public boolean isAuthorizationListEnabled(@NonNull Configuration config) {
        requireNonNull(config);
        return config.getConfigData(ContractsConfig.class).evmPectraEnabled();
    }
}
