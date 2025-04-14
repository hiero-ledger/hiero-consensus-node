// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gossip.modular;

import com.swirlds.base.state.Startable;
import com.swirlds.common.threading.framework.Stoppable;
import com.swirlds.common.threading.framework.Stoppable.StopBehavior;
import com.swirlds.platform.gossip.IntakeEventCounter;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Responsible for main control of gossip activity. Handles {@link #pause()} and {@link #resume()} from {@link GossipController}
 * for runtime control, plus {@link #start()} for initial startup. At the moment {@link #stop()} is not really used,
 * not all resources will be properly stopped/cleaned when calling it and it is not defined if it should be startable again.
 */
public class SyncGossipController implements GossipController {

    private static final Logger logger = LogManager.getLogger(SyncGossipController.class);

    private final SyncGossipSharedProtocolState sharedState;
    private final IntakeEventCounter intakeEventCounter;
    private boolean started = false;
    private final List<Stoppable> startables = new ArrayList<>();

    /**
     * Creates new gossip controller
     * @param intakeEventCounter    keeps track of how many events have been received from each peer
     * @param sharedState           temporary class to share state between various protocols in modularized gossip, to be removed
     */
    public SyncGossipController(
            @NonNull final IntakeEventCounter intakeEventCounter, SyncGossipSharedProtocolState sharedState) {
        this.intakeEventCounter = intakeEventCounter;
        this.sharedState = sharedState;
    }

    /**
     * Registers thread which should be started when {@link #start()} method is called but NOT stopped on {@link #stop()}
     * @param thing thread to start
     */
    public void registerThingToStart(final @NonNull Stoppable thing) {
        Objects.requireNonNull(thing);
        startables.add(thing);
    }

    /**
     * Start gossiping. Spin up all the threads registered in {@link #registerThingToStart(Stoppable)}
     */
    void start() {
        if (started) {
            throw new IllegalStateException("Gossip already started");
        }
        started = true;
        startables.forEach(Startable::start);
    }

    /**
     * Stop gossiping.
     * This method is not fully working. It stops some threads, but leaves others running
     * In particular, you cannot call {@link #start()} () after calling stop (use {@link #pause()}{@link #resume()} as needed)
     */
    void stop() {
        if (!started) {
            throw new IllegalStateException("Gossip not started");
        }
        sharedState.syncManager().haltRequestedObserver("stopping gossip");
        sharedState.gossipHalted().set(true);
        // wait for all existing syncs to stop. no new ones will be started, since gossip has been halted, and
        // we've fallen behind
        sharedState.syncPermitProvider().waitForAllPermitsToBeReleased();

        for (Stoppable startable : startables) {
            startable.stop(StopBehavior.INTERRUPTABLE);
        }
    }

    /**
     * Stop gossiping until {@link #resume()} is called. If called when already paused then this has no effect.
     */
    public void pause() {
        if (!started) {
            throw new IllegalStateException("Gossip not started");
        }
        sharedState.gossipHalted().set(true);
        sharedState.syncPermitProvider().waitForAllPermitsToBeReleased();
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
        sharedState.gossipHalted().set(false);

        // Revoke all permits when we begin gossiping again. Presumably we are behind the pack,
        // and so we want to avoid talking to too many peers at once until we've had a chance
        // to properly catch up.
        sharedState.syncPermitProvider().revokeAll();
    }

    /**
     * Clear the internal state of the gossip engine.
     */
    void clear() {
        sharedState.shadowgraph().clear();
    }
}
