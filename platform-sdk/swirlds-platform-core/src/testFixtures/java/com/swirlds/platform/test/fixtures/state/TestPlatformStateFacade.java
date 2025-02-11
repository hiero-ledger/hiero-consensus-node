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

package com.swirlds.platform.test.fixtures.state;

import com.hedera.hapi.node.base.SemanticVersion;
import com.swirlds.platform.state.PlatformStateModifier;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.platform.system.SoftwareVersion;
import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.Function;

public class TestPlatformStateFacade extends PlatformStateFacade {
    public static final TestPlatformStateFacade TEST_PLATFORM_STATE_FACADE =
            new TestPlatformStateFacade(v -> SoftwareVersion.NO_VERSION);

    public TestPlatformStateFacade(Function<SemanticVersion, SoftwareVersion> versionFactory) {
        super(versionFactory);
    }

    /**
     * The method is made public for testing purposes.
     */
    @NonNull
    @Override
    public PlatformStateModifier getWritablePlatformStateOf(@NonNull State state) {
        return super.getWritablePlatformStateOf(state);
    }
}
