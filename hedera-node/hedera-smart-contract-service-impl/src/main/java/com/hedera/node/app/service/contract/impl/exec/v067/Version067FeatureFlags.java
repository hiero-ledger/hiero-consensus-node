// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.v067;

import com.hedera.node.app.service.contract.impl.exec.v066.Version066FeatureFlags;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class Version067FeatureFlags extends Version066FeatureFlags {

    @Inject
    public Version067FeatureFlags() {
        // Dagger2
    }
}
