// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.util;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.platform.state.signed.ReservedSignedState;
import com.swirlds.platform.system.SwirldMain;

/**
 * Implementation of this interface processes a previously prepared state (applying an event or a block stream)
 */
@FunctionalInterface
public interface AppStateProcessor {
    void initialize(ReservedSignedState state, SwirldMain<?> swirldMain, PlatformContext platformContext)
            throws Exception;
}
