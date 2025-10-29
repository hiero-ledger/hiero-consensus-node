// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.reconnect;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.RECONNECT;
import static com.swirlds.metrics.api.FloatFormats.FORMAT_10_0;
import static com.swirlds.metrics.api.Metrics.PLATFORM_CATEGORY;

import com.swirlds.base.time.Time;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.merkle.synchronization.config.ReconnectConfig;
import com.swirlds.common.metrics.extensions.CountPerSecond;
import com.swirlds.common.threading.manager.ThreadManager;
import com.swirlds.common.utility.throttle.RateLimitedLogger;
import com.swirlds.logging.legacy.payload.ReconnectFailurePayload;
import com.swirlds.logging.legacy.payload.ReconnectFinishPayload;
import com.swirlds.logging.legacy.payload.ReconnectStartPayload;
import com.swirlds.platform.Utilities;
import com.swirlds.platform.config.StateConfig;
import com.swirlds.platform.metrics.ReconnectMetrics;
import com.swirlds.platform.network.Connection;
import com.swirlds.platform.network.NetworkProtocolException;
import com.swirlds.platform.network.NetworkUtils;
import com.swirlds.platform.network.protocol.PeerProtocol;
import com.swirlds.platform.network.protocol.ReservedSignedStatePromise;
import com.swirlds.platform.state.SwirldStateManager;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.platform.state.signed.ReservedSignedState;
import com.swirlds.state.MerkleNodeState;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.status.PlatformStatus;

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
    private final PlatformStateFacade platformStateFacade;
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

    private final Time time;
    private final PlatformContext platformContext;
    private final ReservedSignedStatePromise reservedSignedStatePromise;
    private final SwirldStateManager swirldStateManager;
    private final Function<VirtualMap, MerkleNodeState> createStateFromVirtualMap;

    /**
     * @param threadManager              responsible for creating and managing threads
     * @param peerId                     the ID of the peer we are communicating with
     * @param teacherThrottle            restricts reconnects as a teacher
     * @param lastCompleteSignedState    provides the latest completely signed state
     * @param reconnectSocketTimeout     the socket timeout to use when executing a reconnect
     * @param reconnectMetrics           tracks reconnect metrics
     * @param fallenBehindMonitor        an instance of the fallenBehind Monitor which tracks if the node has fallen behind
     * @param platformStatusSupplier     provides the platform status
     * @param time                       the time object to use
     * @param platformStateFacade        provides access to the platform state
     * @param reservedSignedStatePromise a mechanism to get a SignedState or block while it is not available
     * @param createStateFromVirtualMap  a function to instantiate the state object from a Virtual Map
     */
    public ReconnectStatePeerProtocol(
            @NonNull final PlatformContext platformContext,
            @NonNull final ThreadManager threadManager,
            @NonNull final NodeId peerId,
            @NonNull final ReconnectStateTeacherThrottle teacherThrottle,
            @NonNull final Supplier<ReservedSignedState> lastCompleteSignedState,
            @NonNull final Duration reconnectSocketTimeout,
            @NonNull final ReconnectMetrics reconnectMetrics,
            @NonNull final FallenBehindMonitor fallenBehindMonitor,
            @NonNull final Supplier<PlatformStatus> platformStatusSupplier,
            @NonNull final Time time,
            @NonNull final PlatformStateFacade platformStateFacade,
            @NonNull final ReservedSignedStatePromise reservedSignedStatePromise,
            @NonNull final SwirldStateManager swirldStateManager,
            @NonNull final Function<VirtualMap, MerkleNodeState> createStateFromVirtualMap) {

        this.platformContext = Objects.requireNonNull(platformContext);
        this.threadManager = Objects.requireNonNull(threadManager);
        this.peerId = Objects.requireNonNull(peerId);
        this.teacherThrottle = Objects.requireNonNull(teacherThrottle);
        this.lastCompleteSignedState = Objects.requireNonNull(lastCompleteSignedState);
        this.reconnectSocketTimeout = Objects.requireNonNull(reconnectSocketTimeout);
        this.reconnectMetrics = Objects.requireNonNull(reconnectMetrics);
        this.fallenBehindMonitor = Objects.requireNonNull(fallenBehindMonitor);
        this.platformStatusSupplier = Objects.requireNonNull(platformStatusSupplier);
        this.platformStateFacade = Objects.requireNonNull(platformStateFacade);
        this.reservedSignedStatePromise = Objects.requireNonNull(reservedSignedStatePromise);
        this.swirldStateManager = Objects.requireNonNull(swirldStateManager);
        this.createStateFromVirtualMap = Objects.requireNonNull(createStateFromVirtualMap);
        Objects.requireNonNull(time);

        final Duration minimumTimeBetweenReconnects = platformContext
                .getConfiguration()
                .getConfigData(ReconnectConfig.class)
                .minimumTimeBetweenReconnects();

        stateNullLogger = new RateLimitedLogger(logger, time, minimumTimeBetweenReconnects);
        stateIncompleteLogger = new RateLimitedLogger(logger, time, minimumTimeBetweenReconnects);
        fallenBehindLogger = new RateLimitedLogger(logger, time, minimumTimeBetweenReconnects);
        notActiveLogger = new RateLimitedLogger(logger, time, minimumTimeBetweenReconnects);
        this.time = Objects.requireNonNull(time);

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
        final boolean acquiredPermit = reservedSignedStatePromise.acquire();
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
        reservedSignedStatePromise.release();
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
        if (!reservedSignedStatePromise.tryBlock()) {
            reconnectRejected();
            return false;
        }

        // Check if a reconnect with the learner is permitted by the throttle.
        final boolean reconnectPermittedByThrottle = teacherThrottle.initiateReconnect(peerId);
        if (!reconnectPermittedByThrottle) {
            reconnectRejected();
            reservedSignedStatePromise.release();
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
        reservedSignedStatePromise.release();
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

            final MerkleNodeState consensusState = swirldStateManager.getConsensusState();
            final ReconnectStateLearner learner = new ReconnectStateLearner(
                    platformContext,
                    threadManager,
                    connection,
                    consensusState,
                    reconnectSocketTimeout,
                    reconnectMetrics,
                    platformStateFacade,
                    createStateFromVirtualMap);

            logger.info(RECONNECT.getMarker(), () -> new ReconnectStartPayload(
                            "Starting learner in role of the receiver.",
                            true,
                            connection.getSelfId().id(),
                            connection.getOtherId().id(),
                            //      TODO is this correct?
                            platformStateFacade.roundOf(consensusState))
                    .toString());

            final ReservedSignedState reservedSignedState = learner.execute();

            logger.info(RECONNECT.getMarker(), () -> new ReconnectFinishPayload(
                            "Finished learner in the role of the receiver.",
                            true,
                            connection.getSelfId().id(),
                            connection.getOtherId().id(),
                            reservedSignedState.get().getRound())
                    .toString());
            final var debugHashDepth = platformContext
                    .getConfiguration()
                    .getConfigData(StateConfig.class)
                    .debugHashDepth();
            logger.info(
                    RECONNECT.getMarker(),
                    """
                            Information for state received during learner:
                            {}""",
                    () -> platformStateFacade.getInfoString(
                            reservedSignedState.get().getState(), debugHashDepth));

            reservedSignedStatePromise.provide(reservedSignedState);

        } catch (final RuntimeException | InterruptedException e) {
            if (Utilities.isOrCausedBySocketException(e)) {
                logger.error(EXCEPTION.getMarker(), () -> new ReconnectFailurePayload(
                                "Got socket exception while receiving a signed state! "
                                        + NetworkUtils.formatException(e),
                                ReconnectFailurePayload.CauseOfFailure.SOCKET)
                        .toString());
            } else if (connection != null) {
                connection.disconnect();
            }
            throw new RuntimeException(e);
        }
    }

    /**
     * Perform reconnect as the teacher.
     *
     * @param connection the connection to use for the reconnect
     */
    private void teacher(final Connection connection) {
        try (final ReservedSignedState state = teacherState) {
            new ReconnectStateTeacher(
                            platformContext,
                            time,
                            threadManager,
                            connection,
                            reconnectSocketTimeout,
                            connection.getSelfId(),
                            connection.getOtherId(),
                            state.get().getRound(),
                            reconnectMetrics,
                            platformStateFacade)
                    .execute(state.get());
        } finally {
            teacherThrottle.reconnectAttemptFinished();
            teacherState = null;
            // cancel the permit acquired in shouldAccept() so that we can start learning if we need to
            reservedSignedStatePromise.release();
        }
    }

    private enum InitiatedBy {
        NO_ONE,
        SELF,
        PEER
    }
}
