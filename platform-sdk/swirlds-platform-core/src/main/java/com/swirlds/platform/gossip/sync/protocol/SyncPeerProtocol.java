// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gossip.sync.protocol;

import static org.hiero.base.utility.CompareTo.isGreaterThanOrEqualTo;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.threading.pool.ParallelExecutionException;
import com.swirlds.platform.Utilities;
import com.swirlds.platform.gossip.IntakeEventCounter;
import com.swirlds.platform.gossip.SyncException;
import com.swirlds.platform.gossip.permits.SyncPermitProvider;
import com.swirlds.platform.gossip.shadowgraph.ShadowgraphSynchronizer;
import com.swirlds.platform.metrics.SyncMetrics;
import com.swirlds.platform.network.Connection;
import com.swirlds.platform.network.NetworkProtocolException;
import com.swirlds.platform.network.protocol.PeerProtocol;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.hiero.consensus.gossip.FallenBehindManager;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.status.PlatformStatus;

/**
 * Executes the sync protocol where events are exchanged with a peer and all events are sent and received in topological
 * order.
 * <p>
 * This object will be instantiated once per peer, and is bidirectional
 */
public class SyncPeerProtocol implements PeerProtocol {
    /**
     * The id of the peer being synced with in this protocol
     */
    private final NodeId peerId;

    /**
     * The shadow graph synchronizer, responsible for actually doing the sync
     */
    private final ShadowgraphSynchronizer synchronizer;

    /**
     * Manager to determine whether this node has fallen behind
     */
    private final FallenBehindManager fallenBehindManager;

    /**
     * Metrics tracking syncing
     */
    private final SyncMetrics syncMetrics;

    /**
     * The provider for sync permits
     */
    private final SyncPermitProvider permitProvider;

    /**
     * Keeps track of how many events have been received from each peer, but haven't yet made it through the intake
     * pipeline.
     */
    private final IntakeEventCounter intakeEventCounter;

    /**
     * Returns true if gossip is halted, false otherwise
     */
    private final BooleanSupplier gossipHalted;

    /**
     * The last time this protocol executed
     */
    private Instant lastSyncTime = Instant.MIN;

    /**
     * The amount of time to sleep after a sync
     */
    private final Duration sleepAfterSync;

    private final PlatformContext platformContext;

    private final Supplier<PlatformStatus> platformStatusSupplier;

    /**
     * Constructs a new sync protocol
     *
     * @param platformContext        the platform context
     * @param peerId                 the id of the peer being synced with in this protocol
     * @param synchronizer           the shadow graph synchronizer, responsible for actually doing the sync
     * @param fallenBehindManager    manager to determine whether this node has fallen behind
     * @param permitProvider         provides permits to sync
     * @param intakeEventCounter     keeps track of how many events have been received from each peer, but haven't yet
     *                               made it through the intake pipeline
     * @param gossipHalted           returns true if gossip is halted, false otherwise
     * @param sleepAfterSync         the amount of time to sleep after a sync
     * @param syncMetrics            metrics tracking syncing
     * @param platformStatusSupplier provides the current platform status
     */
    public SyncPeerProtocol(
            @NonNull final PlatformContext platformContext,
            @NonNull final NodeId peerId,
            @NonNull final ShadowgraphSynchronizer synchronizer,
            @NonNull final FallenBehindManager fallenBehindManager,
            @NonNull final SyncPermitProvider permitProvider,
            @NonNull final IntakeEventCounter intakeEventCounter,
            @NonNull final BooleanSupplier gossipHalted,
            @NonNull final Duration sleepAfterSync,
            @NonNull final SyncMetrics syncMetrics,
            @NonNull final Supplier<PlatformStatus> platformStatusSupplier) {

        this.platformContext = Objects.requireNonNull(platformContext);
        this.peerId = Objects.requireNonNull(peerId);
        this.synchronizer = Objects.requireNonNull(synchronizer);
        this.fallenBehindManager = Objects.requireNonNull(fallenBehindManager);
        this.permitProvider = Objects.requireNonNull(permitProvider);
        this.intakeEventCounter = Objects.requireNonNull(intakeEventCounter);
        this.gossipHalted = Objects.requireNonNull(gossipHalted);
        this.sleepAfterSync = Objects.requireNonNull(sleepAfterSync);
        this.syncMetrics = Objects.requireNonNull(syncMetrics);
        this.platformStatusSupplier = Objects.requireNonNull(platformStatusSupplier);
    }

    /**
     * @return true if the cooldown period after a sync has elapsed, else false
     */
    private boolean syncCooldownComplete() {
        final Duration elapsed =
                Duration.between(lastSyncTime, platformContext.getTime().now());

        return isGreaterThanOrEqualTo(elapsed, sleepAfterSync);
    }

    /**
     * Is now the right time to sync?
     *
     * @return true if the node should sync, false otherwise
     */
    private boolean shouldSync() {
        if (!SyncStatusChecker.doesStatusPermitSync(platformStatusSupplier.get())) {
            syncMetrics.doNotSyncPlatformStatus();
            return false;
        }

        if (!syncCooldownComplete()) {
            syncMetrics.doNotSyncCooldown();
            return false;
        }

        if (gossipHalted.getAsBoolean()) {
            syncMetrics.doNotSyncHalted();
            return false;
        }

        if (fallenBehindManager.hasFallenBehind()) {
            syncMetrics.doNotSyncFallenBehind();
            return false;
        }

        if (intakeEventCounter.hasUnprocessedEvents(peerId)) {
            syncMetrics.doNotSyncIntakeCounter();
            return false;
        }

        if (!permitProvider.acquire()) {
            syncMetrics.doNotSyncNoPermits();
            return false;
        }

        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean shouldInitiate() {
        syncMetrics.opportunityToInitiateSync();
        final boolean shouldSync = shouldSync();

        if (shouldSync) {
            syncMetrics.outgoingSyncRequestSent();
        }

        return shouldSync;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean shouldAccept() {
        syncMetrics.incomingSyncRequestReceived();
        final boolean shouldSync = shouldSync();

        if (shouldSync) {
            syncMetrics.acceptedSyncRequest();
        }

        return shouldSync;
    }

    /**
     * Return the existing permit
     */
    private void returnPermit() {
        permitProvider.release();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void initiateFailed() {
        returnPermit();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void acceptFailed() {
        returnPermit();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean acceptOnSimultaneousInitiate() {
        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void runProtocol(@NonNull final Connection connection)
            throws NetworkProtocolException, IOException, InterruptedException {

        try {
            synchronizer.synchronize(platformContext, connection);
        } catch (final ParallelExecutionException | SyncException e) {
            if (Utilities.isRootCauseSuppliedType(e, IOException.class)) {
                throw new IOException(e);
            }

            throw new NetworkProtocolException(e);
        } finally {
            returnPermit();

            lastSyncTime = platformContext.getTime().now();
        }
    }
}
