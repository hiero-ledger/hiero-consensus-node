// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.platform;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.node.app.version.ServicesSoftwareVersion;
import com.swirlds.platform.system.SoftwareVersion;
import dagger.Module;
import dagger.Provides;
import java.util.function.Function;
import javax.inject.Singleton;

@Module
public interface PlatformStateModule {

    @Provides
    @Singleton
    static Function<SemanticVersion, SoftwareVersion> softwareVersionFactory() {
        return ServicesSoftwareVersion::new;
    }
}
