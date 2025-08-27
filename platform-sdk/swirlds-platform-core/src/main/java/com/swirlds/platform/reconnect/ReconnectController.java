// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.reconnect;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.RECONNECT;

import com.swirlds.common.merkle.synchronization.config.ReconnectConfig;
import com.swirlds.common.threading.framework.config.ThreadConfiguration;
import com.swirlds.common.threading.manager.ThreadManager;
import com.swirlds.logging.legacy.LogMarker;
import com.swirlds.platform.state.signed.ReservedSignedState;
import com.swirlds.platform.state.signed.SignedStateValidator;
import com.swirlds.platform.state.snapshot.SignedStateFileReader;
import com.swirlds.platform.system.SystemExitCode;
import com.swirlds.platform.system.SystemExitUtils;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.status.PlatformStatus;

/**
 * Responsible for executing the whole reconnect process
 */
public class ReconnectController implements Runnable {
    private static final Logger logger = LogManager.getLogger(ReconnectController.class);

    private final ReconnectPlatformHelper platformHelper;
    private final ReconnectNetworkHelper networkHelper;
    private final Semaphore threadRunning;
    private final Runnable resumeGossip;
    private final SignedStateValidator validator;
    private final ThreadManager threadManager;
    private final Duration minTimeBetweenReconnects;
    /** throttles reconnect learner attempts */
    private final ReconnectLearnerThrottle reconnectLearnerThrottle;

    private final AtomicReference<PlatformStatus> platformStatus = new AtomicReference<>(PlatformStatus.STARTING_UP);

    /**
     * @param reconnectConfig configuration for reconnect
     * @param threadManager   responsible for creating and managing threads
     * @param platformHelper  executes phases of a reconnect against rest of the platform
     * @param networkHelper   performs reconnect against specific wire implementation (currently gossip network)
     * @param resumeGossip    starts gossip if previously suspended
     * @param validator       validator used to determine if the state received in reconnect has sufficient valid
     *                        signatures.
     */
    public ReconnectController(
            @NonNull final ReconnectConfig reconnectConfig,
            @NonNull final ThreadManager threadManager,
            @NonNull final ReconnectPlatformHelper platformHelper,
            @NonNull final ReconnectNetworkHelper networkHelper,
            @NonNull final Runnable resumeGossip,
            @NonNull final ReconnectLearnerThrottle reconnectLearnerThrottle,
            @NonNull final SignedStateValidator validator) {
        this.threadManager = Objects.requireNonNull(threadManager);
        this.platformHelper = Objects.requireNonNull(platformHelper);
        this.networkHelper = Objects.requireNonNull(networkHelper);
        this.resumeGossip = Objects.requireNonNull(resumeGossip);
        this.reconnectLearnerThrottle = Objects.requireNonNull(reconnectLearnerThrottle);
        this.threadRunning = new Semaphore(1);
        this.minTimeBetweenReconnects = reconnectConfig.minimumTimeBetweenReconnects();
        this.validator = Objects.requireNonNull(validator);
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
        reconnectLearnerThrottle.exitIfReconnectIsDisabled();
        platformHelper.prepareForReconnect();

        logger.info(RECONNECT.getMarker(), "waiting for reconnect connection");
        try {
            logger.info(RECONNECT.getMarker(), "acquired reconnect connection");
            try (final ReservedSignedState reservedState = networkHelper.receiveSignedState(validator)) {
                SignedStateFileReader.registerServiceStates(reservedState.get());
                reconnectLearnerThrottle.successfulReconnect();

                if (!platformHelper.loadSignedState(reservedState.get())) {
                    reconnectLearnerThrottle.handleFailedReconnect();
                    return false;
                }
            }
        } catch (final RuntimeException e) {
            reconnectLearnerThrottle.handleFailedReconnect();
            logger.info(RECONNECT.getMarker(), "receiving signed state failed", e);
            return false;
        }
        resumeGossip.run();
        return true;
    }

    /**
     * Called from the wiring when platform status is changing
     *
     * @param status new platform status
     */
    public void updatePlatformStatus(@NonNull final PlatformStatus status) {
        final PlatformStatus previousState = platformStatus.getAndSet(status);
        if (status != previousState) {
            if (PlatformStatus.BEHIND == status) {
                start();
            }
        }
    }
}
