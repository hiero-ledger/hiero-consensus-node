// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.state;

import com.swirlds.platform.state.PlatformStateModifier;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.NonNull;

public class TestPlatformStateFacade extends PlatformStateFacade {
    /**
     * The method is made public for testing purposes.
     */
    @NonNull
    public static PlatformStateModifier getWritablePlatformStateOf(@NonNull State state) {
        return PlatformStateFacade.getWritablePlatformStateOf(state);
    }
}
