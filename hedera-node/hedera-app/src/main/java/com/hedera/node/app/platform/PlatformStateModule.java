/*
 * Copyright (C) 2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
