// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.network.protocol;

import static com.swirlds.logging.legacy.LogMarker.FREEZE;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.platform.gossip.GossipController;
import com.swirlds.platform.gossip.IntakeEventCounter;
import com.swirlds.platform.gossip.permits.SyncPermitProvider;
import com.swirlds.platform.gossip.shadowgraph.AbstractShadowgraphSynchronizer;
import com.swirlds.platform.gossip.sync.config.SyncConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Base class for synchronization protocols using tipset exchange
 *
 * @param <T> specific shadowgraph synchronizer class which is used by that protocol
 */
public abstract class AbstractSyncProtocol<T extends AbstractShadowgraphSynchronizer>
        implements Protocol, GossipController {

    private static final Logger logger = LogManager.getLogger(AbstractSyncProtocol.class);
    protected final SyncConfig syncConfig;

    private volatile boolean started;
    protected final T synchronizer;
    protected final SyncPermitProvider permitProvider;
    protected final AtomicBoolean gossipHalted = new AtomicBoolean(false);
    protected final IntakeEventCounter intakeEventCounter;

    /**
     * Constructs a new sync protocol
     *
     * @param synchronizer       the shadow graph synchronizer, responsible for actually doing the sync
     * @param platformContext    the platform context
     * @param rosterSize         amount of nodes in the roster, including self
     * @param intakeEventCounter keeps track of how many events have been received from each peer
     */
    protected AbstractSyncProtocol(
            @NonNull final T synchronizer,
            @NonNull final PlatformContext platformContext,
            final int rosterSize,
            IntakeEventCounter intakeEventCounter) {
        this.synchronizer = synchronizer;
        this.intakeEventCounter = Objects.requireNonNull(intakeEventCounter);

        this.syncConfig = platformContext.getConfiguration().getConfigData(SyncConfig.class);
        final int permitCount;
        if (syncConfig.onePermitPerPeer()) {
            permitCount = rosterSize - 1;
        } else {
            permitCount = syncConfig.syncProtocolPermitCount();
        }

        this.permitProvider = new SyncPermitProvider(platformContext, permitCount);
    }

    /**
     * Set total number of permits to previous number + passed difference
     *
     * @param permitsDifference positive to add permits, negative to remove permits
     */
    public void adjustTotalPermits(final int permitsDifference) {
        permitProvider.adjustTotalPermits(permitsDifference);
    }

    /**
     * Start gossiping
     */
    public void start() {
        if (started) {
            throw new IllegalStateException("Gossip already started");
        }
        started = true;
        synchronizer.start();
    }

    /**
     * Stop gossiping. This method is not fully working. It stops some threads, but leaves others running In particular,
     * you cannot call {@link #start()} () after calling stop (use {@link #pause()}{@link #resume()} as needed)
     */
    public void stop() {
        if (!started) {
            throw new IllegalStateException("Gossip not started");
        }
        logger.info(FREEZE.getMarker(), "Gossip frozen, reason: stopping gossip");
        gossipHalted.set(true);
        // wait for all existing syncs to stop. no new ones will be started, since gossip has been halted, and
        // we've fallen behind
        permitProvider.waitForAllPermitsToBeReleased();
        synchronizer.stop();
    }

    /**
     * Stop gossiping until {@link #resume()} is called. If called when already paused then this has no effect.
     */
    public void pause() {
        if (!started) {
            throw new IllegalStateException("Gossip not started");
        }
        gossipHalted.set(true);
        permitProvider.waitForAllPermitsToBeReleased();
    }

    /**
     * Resume gossiping. Undoes the effect of {@link #pause()}. Should be called exactly once after each call to
     * {@link #pause()}.
     */
    public void resume() {
        if (!started) {
            throw new IllegalStateException("Gossip not started");
        }
        intakeEventCounter.reset();
        gossipHalted.set(false);

        // Revoke all permits when we begin gossiping again. Presumably we are behind the pack,
        // and so we want to avoid talking to too many peers at once until we've had a chance
        // to properly catch up.
        permitProvider.revokeAll();
    }

    /**
     * Report the health of the system
     *
     * @param duration duration that the system has been in an unhealthy state
     */
    public void reportUnhealthyDuration(@NonNull final Duration duration) {
        permitProvider.reportUnhealthyDuration(duration);
    }

    /**
     * Clear the internal state of the gossip engine.
     */
    public void clear() {
        synchronizer.clear();
    }
}
