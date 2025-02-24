// SPDX-License-Identifier: Apache-2.0
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
