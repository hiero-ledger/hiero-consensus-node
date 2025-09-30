// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.v067;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.service.contract.impl.exec.v065.Version065FeatureFlags;
import com.hedera.node.config.data.ContractsConfig;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class Version067FeatureFlags extends Version065FeatureFlags {

    @Inject
    public Version067FeatureFlags() {
        // Dagger2
    }

    public boolean isNativeLibVerificationEnabled(@NonNull Configuration config) {
        requireNonNull(config);
        return config.getConfigData(ContractsConfig.class).nativeLibVerificationHaltEnabled();
    }
}
