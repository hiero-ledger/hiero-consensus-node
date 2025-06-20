// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.v050;

import com.hedera.node.app.service.contract.impl.exec.v046.Version046FeatureFlags;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class Version050FeatureFlags extends Version046FeatureFlags {
    @Inject
    public Version050FeatureFlags() {
        // Dagger2
    }
}
