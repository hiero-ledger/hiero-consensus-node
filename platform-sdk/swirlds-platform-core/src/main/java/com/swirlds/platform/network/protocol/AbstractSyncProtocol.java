// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.network.protocol;

import com.swirlds.platform.gossip.GossipController;
import com.swirlds.platform.gossip.shadowgraph.AbstractShadowgraphSynchronizer;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;

public abstract class AbstractSyncProtocol<T extends AbstractShadowgraphSynchronizer>
        implements Protocol, GossipController {

    protected final T synchronizer;

    protected AbstractSyncProtocol(@NonNull final T synchronizer) {
        this.synchronizer = synchronizer;
    }

    /**
     * Set total number of permits to previous number + passed difference
     *
     * @param permitsDifference positive to add permits, negative to remove permits
     */
    public abstract void adjustTotalPermits(int permitsDifference);

    /**
     * Start gossiping
     */
    public abstract void start();

    /**
     * Stop gossiping. This method is not fully working. It stops some threads, but leaves others running In particular,
     * you cannot call {@link #start()} () after calling stop (use {@link #pause()}{@link #resume()} as needed)
     */
    public abstract void stop();

    /**
     * Clear the internal state of the gossip engine.
     */
    public abstract void clear();

    /**
     * Report the health of the system
     *
     * @param duration duration that the system has been in an unhealthy state
     */
    public abstract void reportUnhealthyDuration(Duration duration);

    /**
     * Updates the current event window (mostly ancient thresholds)
     *
     * @param eventWindow new event window to apply
     */
    public void updateEventWindow(@NonNull final EventWindow eventWindow) {
        synchronizer.updateEventWindow(eventWindow);
    }

    /**
     * Events sent here should be gossiped to the network
     *
     * @param platformEvent event to be sent outside
     */
    public void addEvent(@NonNull final PlatformEvent platformEvent) {
        synchronizer.addEvent(platformEvent);
    }
}
