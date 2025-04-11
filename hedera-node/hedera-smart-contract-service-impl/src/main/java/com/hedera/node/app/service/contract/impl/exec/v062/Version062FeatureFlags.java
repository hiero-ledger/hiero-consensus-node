// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.v062;

import com.hedera.node.app.service.contract.impl.exec.v051.Version051FeatureFlags;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class Version062FeatureFlags extends Version051FeatureFlags {
    @Inject
    public Version062FeatureFlags() {
        // Dagger2
    }
}
