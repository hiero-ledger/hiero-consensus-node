// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.reconnect;

import static com.swirlds.logging.legacy.LogMarker.RECONNECT;

import com.swirlds.common.merkle.synchronization.streams.AsyncInputStream;
import com.swirlds.common.merkle.synchronization.utility.MerkleSynchronizationException;
import com.swirlds.virtualmap.internal.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.concurrent.pool.StandardWorkGroup;
import org.hiero.consensus.reconnect.config.ReconnectConfig;

/**
 * A task running on the learner side, which is responsible for getting responses from the teacher.
 *
 * <p>The task keeps running as long as the corresponding {@link LearnerPullVirtualTreeSendTask}
 * is alive, or some responses are expected from the teacher.
 *
 * <p>For every response from the teacher, the learner view is notified, which in turn notifies
 * the current traversal order, so it can recalculate the next virtual path to request.
 */
public class LearnerPullVirtualTreeReceiveTask {

    private static final Logger logger = LogManager.getLogger(LearnerPullVirtualTreeReceiveTask.class);

    private static final String NAME = "reconnect-learner-receiver";

    private final StandardWorkGroup workGroup;
    private final AsyncInputStream in;
    private final LearnerPullVirtualTreeView view;

    // Number of requests sent to teacher / responses expected from the teacher. Increased in
    // sending tasks, decreased in receiving tasks
    private final AtomicLong expectedResponses;

    // Indicates if a response for path 0 (virtual root node) has been received
    private final CountDownLatch rootResponseReceived;

    private final Runnable completeListener;

    private final Duration allMessagesReceivedTimeout;

    /**
     * Create a thread for receiving responses to queries from the teacher.
     *
     * @param workGroup
     * 		the work group that will manage this thread
     * @param in
     * 		the input stream, this object is responsible for closing this when finished
     * @param view
     * 		the view to be used when touching the merkle tree
     */
    public LearnerPullVirtualTreeReceiveTask(
            final ReconnectConfig reconnectConfig,
            final StandardWorkGroup workGroup,
            final AsyncInputStream in,
            final LearnerPullVirtualTreeView view,
            final AtomicLong expectedResponses,
            final CountDownLatch rootResponseReceived,
            final Runnable completeListener) {
        this.workGroup = workGroup;
        this.in = in;
        this.view = view;
        this.expectedResponses = expectedResponses;
        this.rootResponseReceived = rootResponseReceived;
        this.completeListener = completeListener;

        this.allMessagesReceivedTimeout = reconnectConfig.allMessagesReceivedTimeout();
    }

    public void exec() {
        workGroup.execute(NAME, this::run);
    }

    private void run() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                final PullVirtualTreeResponse response =
                        in.readAnticipatedMessage(() -> new PullVirtualTreeResponse(view));
                if (response == null) {
                    if (!in.isAlive()) {
                        break;
                    }
                    Thread.sleep(0, 1);
                    continue;
                }
                final long path = response.getPath();
                if (path != Path.INVALID_PATH) {
                    view.responseReceived(response);
                }
                expectedResponses.decrementAndGet();
                if (path == Path.INVALID_PATH) {
                    logger.info(
                            RECONNECT.getMarker(),
                            "The last response is received," + " {} responses are in progress",
                            expectedResponses.get());
                    // There may be other messages for this view being handled by other threads
                    final long waitStart = System.currentTimeMillis();
                    while (expectedResponses.get() != 0) {
                        Thread.sleep(0, 1);
                        if (System.currentTimeMillis() - waitStart > allMessagesReceivedTimeout.toMillis()) {
                            throw new MerkleSynchronizationException(
                                    "Timed out waiting for view all remaining view messages to be processed");
                        }
                    }
                    completeListener.run();
                    logger.info(RECONNECT.getMarker(), "Learning is complete");
                } else if (response.getPath() == 0) {
                    logger.info(RECONNECT.getMarker(), "Root response received from the teacher");
                    rootResponseReceived.countDown();
                }
            }
        } catch (final Exception ex) {
            workGroup.handleError(ex);
        }
    }
}
