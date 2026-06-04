// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.reconnect;

import static com.swirlds.logging.legacy.LogMarker.RECONNECT;

import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.swirlds.virtualmap.internal.Path;
import com.swirlds.virtualmap.sync.MerkleSynchronizationException;
import com.swirlds.virtualmap.sync.streams.AsyncInputStream;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
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
    private static final long SLOW_APPLY_THRESHOLD_NANOS = 1_000_000L;

    private static final String NAME = "reconnect-learner-receiver";

    private final StandardWorkGroup workGroup;
    private final AsyncInputStream in;
    private final LearnerPullVirtualTreeView view;

    // Number of requests sent to teacher / responses expected from the teacher. Increased in
    // sending tasks, decreased in receiving tasks
    private final AtomicLong expectedResponses;

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
            final AtomicLong expectedResponses) {
        this.workGroup = workGroup;
        this.in = in;
        this.view = view;
        this.expectedResponses = expectedResponses;

        this.allMessagesReceivedTimeout = reconnectConfig.allMessagesReceivedTimeout();
    }

    /**
     * Start the background thread that receives responses from the teacher.
     */
    public void exec() {
        workGroup.execute(NAME, this::run);
    }

    /**
     * Main loop for the receiver thread. Reads responses from the async input stream,
     * tracks reconnect statistics, and delegates to the learner view. Terminates when the
     * stream signals completion via {@link Path#INVALID_PATH}.
     */
    private void run() {
        long readBlockedNanos = 0;
        long parseNanos = 0;
        long applyNanos = 0;
        long drainWaitNanos = 0;
        long maxApplyNanos = 0;
        long slowApplyCount = 0;
        long responseCount = 0;
        final long startNanos = System.nanoTime();

        try {
            while (!Thread.currentThread().isInterrupted()) {
                final long t0 = System.nanoTime();
                final byte[] responseBytes = in.readAnticipatedMessage();
                final long t1 = System.nanoTime();
                if (responseBytes == null) {
                    if (!in.isAlive()) {
                        break;
                    }
                    Thread.sleep(0, 1);
                    continue;
                }
                final PullVirtualTreeResponse response =
                        PullVirtualTreeResponse.parseFrom(BufferedData.wrap(responseBytes));
                final long path = response.path();
                final long t2 = System.nanoTime(); // after parse, before apply
                if (path != Path.INVALID_PATH) {
                    view.responseReceived(response);
                }
                final long t3 = System.nanoTime(); // after apply
                expectedResponses.decrementAndGet();
                if (path == Path.INVALID_PATH) {
                    logger.info(
                            RECONNECT.getMarker(),
                            "The last response is received, {} responses are in progress",
                            expectedResponses.get());
                    // There may be other messages for this view being handled by other threads
                    final long waitStart = System.currentTimeMillis();
                    final long drainStartNanos = System.nanoTime();
                    while (expectedResponses.get() != 0) {
                        Thread.sleep(0, 1);
                        if (System.currentTimeMillis() - waitStart > allMessagesReceivedTimeout.toMillis()) {
                            throw new MerkleSynchronizationException(
                                    "Timed out waiting for view all remaining view messages to be processed");
                        }
                    }
                    drainWaitNanos += System.nanoTime() - drainStartNanos;
                    logger.info(RECONNECT.getMarker(), "Learning is complete");
                }
                final long applySpan = t3 - t2;
                readBlockedNanos += (t1 - t0);
                parseNanos += (t2 - t1);
                applyNanos += applySpan;
                if (applySpan > SLOW_APPLY_THRESHOLD_NANOS) {
                    slowApplyCount++;
                }
                if (applySpan > maxApplyNanos) {
                    maxApplyNanos = applySpan;
                }
                responseCount++;
            }
        } catch (final Exception ex) {
            workGroup.handleError(ex);
        }

        logger.info(
                RECONNECT.getMarker(),
                "Learner receive breakdown: responses={} wallMs={} readBlockedMs={} parseMs={} "
                        + "applyMs={} drainWaitMs={} maxApplyUs={} slowApplies={}",
                responseCount,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos),
                TimeUnit.NANOSECONDS.toMillis(readBlockedNanos),
                TimeUnit.NANOSECONDS.toMillis(parseNanos),
                TimeUnit.NANOSECONDS.toMillis(applyNanos),
                TimeUnit.NANOSECONDS.toMillis(drainWaitNanos),
                TimeUnit.NANOSECONDS.toMicros(maxApplyNanos),
                slowApplyCount);
    }
}
