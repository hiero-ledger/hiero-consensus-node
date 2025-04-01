// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.version;

import com.hedera.hapi.node.base.SemanticVersion;
import dagger.Module;
import dagger.Provides;

@Module
public class SemanticVersionModule {

    @Provides
    public SemanticVersion provideSemanticVersion() {
        return SemanticVersion.DEFAULT;
    }
}
