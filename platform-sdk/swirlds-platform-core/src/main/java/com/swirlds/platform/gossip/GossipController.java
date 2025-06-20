// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gossip;

/**
 * Minimal interface for controlling main sync gossip intake. Used by reconnect protocol to stop gossiping while
 * resynchronization of all events are done and to resume it after everything is done.
 */
public interface GossipController {

    /**
     * Stop gossiping until {@link #resume()} is called. If called when already paused then this has no effect.
     */
    void pause();

    /**
     * Resume gossiping. Undoes the effect of {@link #pause()}. Should be called exactly once after each call to
     * {@link #pause()}.
     */
    void resume();
}
