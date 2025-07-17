// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.v064;

import com.hedera.node.app.service.contract.impl.exec.v051.Version051FeatureFlags;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class Version064FeatureFlags extends Version051FeatureFlags {
    @Inject
    public Version064FeatureFlags() {
        // Dagger2
    }
}
