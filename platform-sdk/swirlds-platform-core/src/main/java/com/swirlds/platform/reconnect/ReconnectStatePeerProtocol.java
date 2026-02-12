// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.reconnect;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.RECONNECT;
import static com.swirlds.metrics.api.FloatFormats.FORMAT_10_0;
import static com.swirlds.metrics.api.Metrics.PLATFORM_CATEGORY;
import static java.util.Objects.requireNonNull;
import static org.hiero.consensus.platformstate.PlatformStateUtils.getInfoString;
import static org.hiero.consensus.platformstate.PlatformStateUtils.roundOf;

import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import com.swirlds.logging.legacy.payload.ReconnectFinishPayload;
import com.swirlds.logging.legacy.payload.ReconnectStartPayload;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.metrics.ReconnectMetrics;
import com.swirlds.state.StateLifecycleManager;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.time.Duration;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.concurrent.BlockingResourceProvider;
import org.hiero.consensus.concurrent.manager.ThreadManager;
import org.hiero.consensus.concurrent.utility.throttle.RateLimitedLogger;
import org.hiero.consensus.exceptions.ThrowableUtilities;
import org.hiero.consensus.gossip.ReservedSignedStateResult;
import org.hiero.consensus.gossip.impl.network.Connection;
import org.hiero.consensus.gossip.impl.network.NetworkProtocolException;
import org.hiero.consensus.gossip.impl.network.protocol.PeerProtocol;
import org.hiero.consensus.metrics.extensions.CountPerSecond;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.monitoring.FallenBehindMonitor;
import org.hiero.consensus.reconnect.config.ReconnectConfig;
import org.hiero.consensus.state.config.StateConfig;
import org.hiero.consensus.state.signed.ReservedSignedState;
import org.hiero.consensus.state.signed.SignedState;

/**
 * This protocol is responsible for synchronizing an out of date state either local acting as lerner or remote acting as teacher
 * with one particular peer in the network.
 */
public class ReconnectStatePeerProtocol implements PeerProtocol {

    private static final Logger logger = LogManager.getLogger(ReconnectStatePeerProtocol.class);

    private final NodeId peerId;
    private final ReconnectStateTeacherThrottle teacherThrottle;
    private final Supplier<ReservedSignedState> lastCompleteSignedState;
    private final Duration reconnectSocketTimeout;
    private final ReconnectMetrics reconnectMetrics;
    private final CountPerSecond reconnectRejectionMetrics;
    private InitiatedBy initiatedBy = InitiatedBy.NO_ONE;
    private final ThreadManager threadManager;
    private final FallenBehindMonitor fallenBehindMonitor;

    /**
     * Provides the platform status.
     */
    private final Supplier<PlatformStatus> platformStatusSupplier;

    private ReservedSignedState teacherState;
    /**
     * A rate limited logger for when rejecting teacher role due to state being null.
     */
    private final RateLimitedLogger stateNullLogger;
    /**
     * A rate limited logger for when rejecting teacher role due to state being incomplete.
     */
    private final RateLimitedLogger stateIncompleteLogger;
    /**
     * A rate limited logger for when rejecting teacher role due to falling behind.
     */
    private final RateLimitedLogger fallenBehindLogger;
    /**
     * A rate limited logger for when rejecting teacher role due to not having a status of ACTIVE
     */
    private final RateLimitedLogger notActiveLogger;

    private final Configuration configuration;
    private final Metrics metrics;
    private final Time time;
    private final BlockingResourceProvider<ReservedSignedStateResult> reservedSignedStateResultProvider;
    private final StateLifecycleManager<VirtualMapState, VirtualMap> stateLifecycleManager;

    /**
     * Creates a new reconnect protocol instance.
     *
     * @param configuration the platform configuration
     * @param metrics the metrics system
     * @param time the time object to use
     * @param threadManager responsible for creating and managing threads
     * @param peerId the ID of the peer we are communicating with
     * @param teacherThrottle restricts reconnects as a teacher
     * @param lastCompleteSignedState provides the latest completely signed state
     * @param reconnectSocketTimeout the socket timeout to use when executing a reconnect
     * @param reconnectMetrics tracks reconnect metrics
     * @param fallenBehindMonitor an instance of the fallenBehind Monitor which tracks if the node has fallen behind
     * @param platformStatusSupplier provides the platform status
     * @param reservedSignedStateResultProvider a mechanism to get a SignedState or block while it is not available
     * @param stateLifecycleManager the state lifecycle manager
     */
    public ReconnectStatePeerProtocol(
            @NonNull final Configuration configuration,
            @NonNull final Metrics metrics,
            @NonNull final Time time,
            @NonNull final ThreadManager threadManager,
            @NonNull final NodeId peerId,
            @NonNull final ReconnectStateTeacherThrottle teacherThrottle,
            @NonNull final Supplier<ReservedSignedState> lastCompleteSignedState,
            @NonNull final Duration reconnectSocketTimeout,
            @NonNull final ReconnectMetrics reconnectMetrics,
            @NonNull final FallenBehindMonitor fallenBehindMonitor,
            @NonNull final Supplier<PlatformStatus> platformStatusSupplier,
            @NonNull final BlockingResourceProvider<ReservedSignedStateResult> reservedSignedStateResultProvider,
            @NonNull final StateLifecycleManager stateLifecycleManager) {

        this.configuration = requireNonNull(configuration);
        this.metrics = requireNonNull(metrics);
        this.time = requireNonNull(time);
        this.threadManager = requireNonNull(threadManager);
        this.peerId = requireNonNull(peerId);
        this.teacherThrottle = requireNonNull(teacherThrottle);
        this.lastCompleteSignedState = requireNonNull(lastCompleteSignedState);
        this.reconnectSocketTimeout = requireNonNull(reconnectSocketTimeout);
        this.reconnectMetrics = requireNonNull(reconnectMetrics);
        this.fallenBehindMonitor = requireNonNull(fallenBehindMonitor);
        this.platformStatusSupplier = requireNonNull(platformStatusSupplier);
        this.reservedSignedStateResultProvider = requireNonNull(reservedSignedStateResultProvider);
        this.stateLifecycleManager = requireNonNull(stateLifecycleManager);

        final Duration minimumTimeBetweenReconnects =
                configuration.getConfigData(ReconnectConfig.class).minimumTimeBetweenReconnects();

        stateNullLogger = new RateLimitedLogger(logger, time, minimumTimeBetweenReconnects);
        stateIncompleteLogger = new RateLimitedLogger(logger, time, minimumTimeBetweenReconnects);
        fallenBehindLogger = new RateLimitedLogger(logger, time, minimumTimeBetweenReconnects);
        notActiveLogger = new RateLimitedLogger(logger, time, minimumTimeBetweenReconnects);

        this.reconnectRejectionMetrics = new CountPerSecond(
                reconnectMetrics.getMetrics(),
                new CountPerSecond.Config(
                                PLATFORM_CATEGORY, String.format("reconnectRejections_per_sec_%02d", peerId.id()))
                        .withDescription(String.format(
                                "number of reconnections rejected per second from node %02d", peerId.id()))
                        .withUnit("rejectionsPerSec")
                        .withFormat(FORMAT_10_0));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean shouldInitiate() {
        // if this neighbor has not told me I have fallen behind, I will not reconnect with him
        if (!fallenBehindMonitor.hasFallenBehind()) {
            return false;
        }
        if (!fallenBehindMonitor.isBehindPeer(peerId)) {
            return false;
        }

        // if a permit is acquired, it will be released by either initiateFailed or runProtocol
        final boolean acquiredPermit = reservedSignedStateResultProvider.acquireProvidePermit();
        if (acquiredPermit) {
            initiatedBy = InitiatedBy.SELF;
        }
        return acquiredPermit;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void initiateFailed() {
        reservedSignedStateResultProvider.releaseProvidePermit();
        initiatedBy = InitiatedBy.NO_ONE;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean shouldAccept() {
        // we should not be the teacher if we have fallen behind
        if (fallenBehindMonitor.hasFallenBehind()) {
            fallenBehindLogger.info(
                    RECONNECT.getMarker(),
                    "Rejecting reconnect request from node {} because this node has fallen behind",
                    peerId);
            reconnectRejected();
            return false;
        }

        // only teach if the platform is active
        if (platformStatusSupplier.get() != PlatformStatus.ACTIVE) {
            notActiveLogger.info(
                    RECONNECT.getMarker(),
                    "Rejecting reconnect request from node {} because this node isn't ACTIVE",
                    peerId);
            reconnectRejected();
            return false;
        }

        // Check if we have a state that is legal to send to a learner.
        teacherState = lastCompleteSignedState.get();

        if (teacherState == null || teacherState.isNull()) {
            stateNullLogger.info(
                    RECONNECT.getMarker(),
                    "Rejecting reconnect request from node {} due to lack of a fully signed state",
                    peerId);
            reconnectRejected();
            return false;
        }

        if (!teacherState.get().isComplete()) {
            // this is only possible if signed state manager violates its contractual obligations
            stateIncompleteLogger.error(
                    EXCEPTION.getMarker(),
                    "Rejecting reconnect request from node {} due to lack of a fully signed state."
                            + " The signed state manager attempted to provide a state that was not"
                            + " fully signed, which should not be possible.",
                    peerId);
            reconnectRejected();
            return false;
        }

        // we should not become a learner while we are teaching
        // this can happen if we fall behind while we are teaching
        // in this case, we want to finish teaching before we start learning
        // so we acquire the learner permit and release it when we are done teaching
        if (!reservedSignedStateResultProvider.tryBlockProvidePermit()) {
            reconnectRejected();
            return false;
        }

        // Check if a reconnect with the learner is permitted by the throttle.
        final boolean reconnectPermittedByThrottle = teacherThrottle.initiateReconnect(peerId);
        if (!reconnectPermittedByThrottle) {
            reconnectRejected();
            reservedSignedStateResultProvider.releaseProvidePermit();
            return false;
        }

        initiatedBy = InitiatedBy.PEER;
        return true;
    }

    /**
     * Called when we reject a reconnect as a teacher
     */
    private void reconnectRejected() {
        if (teacherState != null) {
            teacherState.close();
            teacherState = null;
        }
        reconnectRejectionMetrics.count();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void acceptFailed() {
        teacherState.close();
        teacherState = null;
        teacherThrottle.reconnectAttemptFinished();
        // cancel the permit acquired in shouldAccept() so that we can start learning if we need to
        reservedSignedStateResultProvider.releaseProvidePermit();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean acceptOnSimultaneousInitiate() {
        // if both nodes fall behind, it makes no sense to reconnect with each other
        // also, it would not be clear who the teacher and who the learner is
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void runProtocol(final Connection connection)
            throws NetworkProtocolException, IOException, InterruptedException {
        try {
            switch (initiatedBy) {
                case PEER -> teacher(connection);
                case SELF -> learner(connection);
                default ->
                    throw new NetworkProtocolException(
                            "runProtocol() called but it is unclear who the teacher and who the learner is");
            }
        } finally {
            initiatedBy = InitiatedBy.NO_ONE;
        }
    }

    /**
     * Perform reconnect as the learner.
     *
     * @param connection the connection to use for the reconnect
     */
    private void learner(final Connection connection) {
        try {
            final VirtualMapState consensusState = stateLifecycleManager.getMutableState();
            final ReconnectStateLearner learner = new ReconnectStateLearner(
                    configuration,
                    metrics,
                    threadManager,
                    connection,
                    consensusState,
                    reconnectSocketTimeout,
                    reconnectMetrics,
                    stateLifecycleManager);

            logger.info(RECONNECT.getMarker(), () -> new ReconnectStartPayload(
                            "Starting reconnect in role of the receiver.",
                            true,
                            connection.getSelfId().id(),
                            connection.getOtherId().id(),
                            roundOf(consensusState))
                    .toString());

            final ReservedSignedState reservedSignedState = learner.execute();

            logger.info(RECONNECT.getMarker(), () -> new ReconnectFinishPayload(
                            "Finished reconnect in the role of the receiver.",
                            true,
                            connection.getSelfId().id(),
                            connection.getOtherId().id(),
                            reservedSignedState.get().getRound())
                    .toString());
            final var debugHashDepth =
                    configuration.getConfigData(StateConfig.class).debugHashDepth();
            logger.info(
                    RECONNECT.getMarker(),
                    """
                            Information for state received during reconnect:
                            {}""",
                    () -> getInfoString(reservedSignedState.get().getState()));

            reservedSignedStateResultProvider.provide(new ReservedSignedStateResult(reservedSignedState, null));

        } catch (final RuntimeException e) {
            if (!ThrowableUtilities.isOrCausedBySocketException(e)) {
                // We are closing the connection as we don't know the state in which is in
                // it might contain non-read bytes.
                if (connection != null) {
                    connection.disconnect();
                }
            }
            try {
                reservedSignedStateResultProvider.provide(new ReservedSignedStateResult(null, e));
            } catch (InterruptedException ie) {
                reservedSignedStateResultProvider.releaseProvidePermit();
            }
        } catch (InterruptedException e) {
            reservedSignedStateResultProvider.releaseProvidePermit();
        }
    }

    /**
     * Perform reconnect as the teacher.
     *
     * @param connection the connection to use for the reconnect
     */
    private void teacher(final Connection connection) {
        try {
            final SignedState state = teacherState.get();
            final ReconnectStateTeacher teacher;
            try {
                teacher = new ReconnectStateTeacher(
                        configuration,
                        time,
                        threadManager,
                        connection,
                        reconnectSocketTimeout,
                        connection.getSelfId(),
                        connection.getOtherId(),
                        state.getRound(),
                        state,
                        reconnectMetrics);
            } finally {
                // The teacher now has all the information needed to teach. In particular, teacher view
                // object initialized by the teacher is a snapshot of all data in the state. It's time to
                // release the original state
                teacherState.close();
            }
            teacher.execute();
        } finally {
            teacherThrottle.reconnectAttemptFinished();
            teacherState = null;
            // cancel the permit acquired in shouldAccept() so that we can start learning if we need to
            reservedSignedStateResultProvider.releaseProvidePermit();
        }
    }

    private enum InitiatedBy {
        NO_ONE,
        SELF,
        PEER
    }
}
