// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.platform;

import com.swirlds.common.utility.AutoCloseableWrapper;
import java.util.HashMap;
import java.util.Map;
import org.hiero.consensus.model.node.NodeId;

/**
 * PTT was written with some state access patterns that are unsafe. This class enables those patterns to be used, even
 * though the platform no longer supports them.
 *
 * @deprecated this is unsafe, don't use it
 */
@Deprecated(forRemoval = true)
public final class UnsafeMutablePTTStateAccessor {

    /**
     * A map is used to be compatible with running multiple PTT nodes in the same JVM.
     */
    private final Map<NodeId, PlatformTestingToolState> states = new HashMap<>();

    private static final UnsafeMutablePTTStateAccessor INSTANCE = new UnsafeMutablePTTStateAccessor();

    private UnsafeMutablePTTStateAccessor() {}

    /**
     * Get the singleton instance.
     *
     * @return the instance
     */
    public static UnsafeMutablePTTStateAccessor getInstance() {
        return INSTANCE;
    }

    /**
     * Set the most recent mutable copy of the PTT state.
     *
     * @param nodeId the node ID
     * @param state  the state
     */
    public synchronized void setMutableState(final NodeId nodeId, final PlatformTestingToolState state) {
        state.reserve();
        final PlatformTestingToolState previousState = states.put(nodeId, state);
        if (previousState != null) {
            previousState.release();
        }
    }

    /**
     * Get the most recent mutable copy of the PTT state. State might not actually be mutable, its mutability changes on
     * a background thread. This state is not safe to read. This state is not safe to write. May return a wrapper around
     * null if called before init() is called on the first PTT state.
     *
     * @param nodeId the node ID
     * @return the state, or a wrapper around null
     */
    public synchronized AutoCloseableWrapper<PlatformTestingToolState> getUnsafeMutableState(final NodeId nodeId) {
        final PlatformTestingToolState state = states.get(nodeId);
        if (state == null) {
            return new AutoCloseableWrapper<>(null, () -> {});
        }
        state.reserve();
        return new AutoCloseableWrapper<>(state, state::release);
    }
}
