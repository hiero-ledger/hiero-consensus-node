// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.v063;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.service.contract.impl.exec.v062.Version062FeatureFlags;
import com.hedera.node.config.data.ContractsConfig;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class Version063FeatureFlags extends Version062FeatureFlags {

    @Inject
    public Version063FeatureFlags() {
        // Dagger2
    }

    public boolean isNativeLibVerificationEnabled(@NonNull Configuration config) {
        requireNonNull(config);
        return config.getConfigData(ContractsConfig.class).nativeLibVerificationHaltEnabled();
    }
}
