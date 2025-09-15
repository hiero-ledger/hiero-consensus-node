// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.v070;

import com.hedera.node.app.service.contract.impl.exec.v065.Version065FeatureFlags;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class Version070FeatureFlags extends Version065FeatureFlags {
    @Inject
    public Version070FeatureFlags() {
        // Dagger2
    }

    @Override
    public boolean isAuthorizationListEnabled() {
        return true;
    }
}
