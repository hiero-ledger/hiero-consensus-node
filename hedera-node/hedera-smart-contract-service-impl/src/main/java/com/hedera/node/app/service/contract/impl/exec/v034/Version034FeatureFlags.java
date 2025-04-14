// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.v034;

import com.hedera.node.app.service.contract.impl.exec.FeatureFlags;
import com.hedera.node.app.service.contract.impl.exec.v030.Version030FeatureFlags;
import com.hedera.node.config.data.AutoCreationConfig;
import com.hedera.node.config.data.LazyCreationConfig;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * The v0.34+ implementation of {@link FeatureFlags}; lazy creation enabled if config says so.
 */
@Singleton
public class Version034FeatureFlags extends Version030FeatureFlags {
    @Inject
    public Version034FeatureFlags() {
        // Dagger2
    }

    @Override
    public boolean isImplicitCreationEnabled(@NonNull final Configuration config) {
        return config.getConfigData(AutoCreationConfig.class).enabled()
                && config.getConfigData(LazyCreationConfig.class).enabled();
    }
}
