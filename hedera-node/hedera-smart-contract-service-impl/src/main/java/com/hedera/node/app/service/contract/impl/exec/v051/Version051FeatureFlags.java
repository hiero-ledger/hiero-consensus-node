// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.v051;

import com.hedera.node.app.service.contract.impl.exec.v050.Version050FeatureFlags;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class Version051FeatureFlags extends Version050FeatureFlags {
    @Inject
    public Version051FeatureFlags() {
        // Dagger2
    }
}
