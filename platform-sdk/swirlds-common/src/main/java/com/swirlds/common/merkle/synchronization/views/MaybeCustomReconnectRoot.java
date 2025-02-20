// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.synchronization.views;

/**
 * A node that is possibly the root of a tree with a custom reconnect root.
 */
public interface MaybeCustomReconnectRoot {

    /**
     * Return true if this node is the root of a subtree that has a custom view for reconnect. Nodes that return
     * true must implement the interface {@link CustomReconnectRoot}.
     *
     * @return true if the node has a custom view to be used during reconnect
     */
    default boolean hasCustomReconnectView() {
        return false;
    }
}
