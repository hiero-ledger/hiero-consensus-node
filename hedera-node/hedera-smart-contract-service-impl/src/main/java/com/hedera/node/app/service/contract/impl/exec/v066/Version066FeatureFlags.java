// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.v066;

import com.hedera.node.app.service.contract.impl.exec.v065.Version065FeatureFlags;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class Version066FeatureFlags extends Version065FeatureFlags {

    @Inject
    public Version066FeatureFlags() {
        // Dagger2
    }
}
