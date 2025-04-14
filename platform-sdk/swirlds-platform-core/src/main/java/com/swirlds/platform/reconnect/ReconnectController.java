// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.reconnect;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.RECONNECT;

import com.swirlds.common.merkle.synchronization.config.ReconnectConfig;
import com.swirlds.common.threading.BlockingResourceProvider;
import com.swirlds.common.threading.framework.config.ThreadConfiguration;
import com.swirlds.common.threading.locks.locked.LockedResource;
import com.swirlds.common.threading.manager.ThreadManager;
import com.swirlds.logging.legacy.LogMarker;
import com.swirlds.platform.network.Connection;
import com.swirlds.platform.state.signed.ReservedSignedState;
import com.swirlds.platform.state.signed.SignedStateValidator;
import com.swirlds.platform.system.SystemExitCode;
import com.swirlds.platform.system.SystemExitUtils;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Responsible for executing the whole reconnect process
 */
public class ReconnectController implements Runnable {
    private static final Logger logger = LogManager.getLogger(ReconnectController.class);

    private final ReconnectHelper helper;
    private final Semaphore threadRunning;
    private final BlockingResourceProvider<Connection> connectionProvider;
    private final Runnable resumeGossip;
    private final AtomicReference<SignedStateValidator> validator = new AtomicReference<>();
    private final ThreadManager threadManager;
    private final Duration minTimeBetweenReconnects;

    /**
     * @param reconnectConfig configuration for reconnect
     * @param threadManager   responsible for creating and managing threads
     * @param helper          executes phases of a reconnect
     * @param resumeGossip    starts gossip if previously suspended
     */
    public ReconnectController(
            @NonNull final ReconnectConfig reconnectConfig,
            @NonNull final ThreadManager threadManager,
            @NonNull final ReconnectHelper helper,
            @NonNull final Runnable resumeGossip) {
        this.threadManager = Objects.requireNonNull(threadManager);
        this.helper = Objects.requireNonNull(helper);
        this.resumeGossip = Objects.requireNonNull(resumeGossip);
        this.threadRunning = new Semaphore(1);
        this.connectionProvider = new BlockingResourceProvider<>();
        this.minTimeBetweenReconnects = reconnectConfig.minimumTimeBetweenReconnects();
    }

    /**
     * Starts the reconnect controller thread if it's not already running
     */
    public void start() {
        if (!threadRunning.tryAcquire()) {
            logger.error(EXCEPTION.getMarker(), "Attempting to start reconnect controller while its already running");
            return;
        }
        logger.info(LogMarker.RECONNECT.getMarker(), "Starting ReconnectController");
        new ThreadConfiguration(threadManager)
                .setComponent("reconnect")
                .setThreadName("reconnect-controller")
                .setRunnable(this)
                .build(true /*start*/);
    }

    @Override
    public void run() {
        try {
            // the ReconnectHelper uses a ReconnectLearnerThrottle to exit if there are too many failed attempts
            // so in this thread we can just try until it succeeds or the throttle kicks in
            while (!executeReconnect()) {
                logger.error(EXCEPTION.getMarker(), "Reconnect failed, retrying");
                Thread.sleep(minTimeBetweenReconnects.toMillis());
            }
        } catch (final RuntimeException | InterruptedException e) {
            logger.error(EXCEPTION.getMarker(), "Unexpected error occurred while reconnecting", e);
            SystemExitUtils.exitSystem(SystemExitCode.RECONNECT_FAILURE);
        } finally {
            threadRunning.release();
        }
    }

    private boolean executeReconnect() throws InterruptedException {
        helper.prepareForReconnect();

        logger.info(RECONNECT.getMarker(), "waiting for reconnect connection");
        try (final LockedResource<Connection> connection = connectionProvider.waitForResource()) {
            logger.info(RECONNECT.getMarker(), "acquired reconnect connection");
            try (final ReservedSignedState reservedState =
                    helper.receiveSignedState(connection.getResource(), validator.get())) {

                if (!helper.loadSignedState(reservedState.get())) {
                    return false;
                }
            }
        } catch (final RuntimeException e) {
            logger.info(RECONNECT.getMarker(), "receiving signed state failed", e);
            return false;
        }
        resumeGossip.run();
        return true;
    }

    /**
     * Try to acquire a permit for negotiate a reconnect in the role of the learner
     *
     * @return true if the permit has been acquired
     */
    public boolean acquireLearnerPermit() {
        return connectionProvider.acquireProvidePermit();
    }

    /**
     * Try to block the learner permit for reconnect. The method {@link #cancelLearnerPermit()} should be called
     * to unblock the permit.
     *
     * @return true if the permit has been blocked
     */
    public boolean blockLearnerPermit() {
        return connectionProvider.tryBlockProvidePermit();
    }

    /**
     * Releases a previously acquired permit for reconnect
     */
    public void cancelLearnerPermit() {
        connectionProvider.releaseProvidePermit();
    }

    /**
     * Provides a connection over which a reconnect learner has been already negotiated. This method should only be
     * called if {@link #acquireLearnerPermit()} has returned true previously. This method blocks until the reconnect is
     * done.
     *
     * @param connection
     * 		the connection to use to execute the reconnect learner protocol
     * @throws InterruptedException
     * 		if the calling thread is interrupted while the connection is being used
     */
    public void provideLearnerConnection(final Connection connection) throws InterruptedException {
        connectionProvider.provide(connection);
    }

    /**
     * Sets the validator used to determine if the state received in reconnect has sufficient valid signatures.
     *
     * @param validator
     * 		the validator to use
     */
    public void setStateValidator(final SignedStateValidator validator) {
        this.validator.set(validator);
    }
}
