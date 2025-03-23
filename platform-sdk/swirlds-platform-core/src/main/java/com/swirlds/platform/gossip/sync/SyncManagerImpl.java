// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gossip.sync;

import static com.swirlds.logging.legacy.LogMarker.FREEZE;
import static com.swirlds.metrics.api.Metrics.INTERNAL_CATEGORY;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.metrics.FunctionGauge;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.gossip.FallenBehindManager;
import org.hiero.consensus.model.node.NodeId;

/**
 * A class that manages information about who we need to sync with, and whether we need to reconnect
 */
public class SyncManagerImpl implements FallenBehindManager {

    private static final Logger logger = LogManager.getLogger(SyncManagerImpl.class);

    /** This object holds data on how nodes are connected to each other. */
    private final FallenBehindManager fallenBehindManager;

    /**
     * Creates a new SyncManager
     *
     * @param platformContext         the platform context
     * @param fallenBehindManager     the fallen behind manager
     */
    public SyncManagerImpl(
            @NonNull final PlatformContext platformContext, @NonNull final FallenBehindManager fallenBehindManager) {

        this.fallenBehindManager = Objects.requireNonNull(fallenBehindManager);

        platformContext
                .getMetrics()
                .getOrCreate(new FunctionGauge.Config<>(
                                INTERNAL_CATEGORY, "hasFallenBehind", Object.class, this::hasFallenBehind)
                        .withDescription("has this node fallen behind?"));
        platformContext
                .getMetrics()
                .getOrCreate(new FunctionGauge.Config<>(
                                INTERNAL_CATEGORY,
                                "numReportFallenBehind",
                                Integer.class,
                                this::numReportedFallenBehind)
                        .withDescription("the number of nodes that have fallen behind")
                        .withUnit("count"));
    }

    /**
     * Observers halt requested dispatches. Causes gossip to permanently stop (until node reboot).
     *
     * @param reason the reason why gossip is being stopped
     */
    public void haltRequestedObserver(final String reason) {
        logger.info(FREEZE.getMarker(), "Gossip frozen, reason: {}", reason);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void reportFallenBehind(final NodeId id) {
        fallenBehindManager.reportFallenBehind(id);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void resetFallenBehind() {
        fallenBehindManager.resetFallenBehind();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean hasFallenBehind() {
        return fallenBehindManager.hasFallenBehind();
    }

    @Override
    public boolean shouldReconnectFrom(final NodeId peerId) {
        return fallenBehindManager.shouldReconnectFrom(peerId);
    }

    @Override
    public int numReportedFallenBehind() {
        return fallenBehindManager.numReportedFallenBehind();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addRemovePeers(@NonNull final Set<NodeId> added, @NonNull final Set<NodeId> removed) {
        fallenBehindManager.addRemovePeers(added, removed);
    }
}
