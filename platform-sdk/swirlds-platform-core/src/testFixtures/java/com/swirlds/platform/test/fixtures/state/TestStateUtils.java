// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.state;

import com.swirlds.state.StateLifecycleManager;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.virtualmap.VirtualMap;

public final class TestStateUtils {

    /**
     * Destroys the given state lifecycle manager by releasing its mutable and latest immutable states.
     * @param stateLifecycleManager the state lifecycle manager to destroy
     */
    public static void destroyStateLifecycleManager(
            final StateLifecycleManager<VirtualMapState, VirtualMap> stateLifecycleManager) {
        if (!stateLifecycleManager.getMutableState().isDestroyed()) {
            stateLifecycleManager.getMutableState().release();
        }
        if (stateLifecycleManager.getLatestImmutableState() != null
                && !stateLifecycleManager.getLatestImmutableState().isDestroyed()) {
            stateLifecycleManager.getLatestImmutableState().release();
        }
    }

    private TestStateUtils() {}
}
