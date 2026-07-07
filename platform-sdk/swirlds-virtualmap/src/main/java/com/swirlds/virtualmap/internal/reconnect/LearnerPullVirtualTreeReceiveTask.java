// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.reconnect;

import static com.swirlds.logging.legacy.LogMarker.RECONNECT;

import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.swirlds.virtualmap.sync.LearnerTreeExchanger;
import com.swirlds.virtualmap.sync.streams.AsyncInputStream;
import com.swirlds.virtualmap.sync.streams.YieldStrategy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A task running on the learner side, which is responsible for getting responses from the teacher.
 * <p>
 * This tasks terminates either on exception or when no more messages are provided by {@link AsyncInputStream}.
 * <p>
 * For every response from the teacher, the learner view is notified, which in turn notifies
 * the current traversal order, so it can recalculate the next virtual path to request.
 */
public class LearnerPullVirtualTreeReceiveTask implements Runnable {

    private static final Logger logger = LogManager.getLogger(LearnerPullVirtualTreeReceiveTask.class);

    // Apply spans above this threshold are counted as "slow" for the diagnostic breakdown.
    private static final long SLOW_APPLY_THRESHOLD_NANOS = 1_000_000L;

    private final AsyncInputStream in;
    private final LearnerTreeExchanger treeExchanger;
    private final CountDownLatch receiveTasksDone;

    /**
     * Create a thread for receiving responses to queries from the teacher.
     *
     * @param in
     * 		the input stream, this object is responsible for closing this when finished
     * @param treeExchanger
     * 		the exchanger used to callback on tree node received
     * @param receiveTasksDone
     * 		latch counted down when this receiver finishes; lets the ordered leaf-apply thread know
     * 		when no further responses will arrive
     */
    public LearnerPullVirtualTreeReceiveTask(
            final AsyncInputStream in,
            final LearnerTreeExchanger treeExchanger,
            final CountDownLatch receiveTasksDone) {
        this.in = in;
        this.treeExchanger = treeExchanger;
        this.receiveTasksDone = receiveTasksDone;
    }

    /**
     * Main loop for the receiver thread. Reads responses from the async input stream,
     * tracks reconnect statistics, and delegates to the learner view.
     * Terminates when input streams returns no more messages to process.
     */
    @Override
    public void run() {
        long readBlockedNanos = 0; // waiting for a response to arrive
        long parseNanos = 0; // PBJ parse to a response object
        long applyNanos = 0; // responseReceived (eager store + enqueue; ordered supply is on the applier)
        long maxApplyNanos = 0;
        long slowApplyCount = 0;
        long responseCount = 0;
        final long startNanos = System.nanoTime();
        try {
            while (!Thread.currentThread().isInterrupted()) {
                final long t0 = System.nanoTime();
                final byte[] responseBytes = in.readOrWait(YieldStrategy.SLEEP);
                final long t1 = System.nanoTime();
                if (responseBytes == null) {
                    break;
                }
                final PullVirtualTreeResponse response =
                        PullVirtualTreeResponse.parseFrom(BufferedData.wrap(responseBytes));
                final long t2 = System.nanoTime();

                if (response.path() < 0) {
                    throw new IllegalStateException("Invalid path received from learner: " + response.path());
                }
                treeExchanger.responseReceived(response);
                final long t3 = System.nanoTime();

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
        } finally {
            // Always signal completion, even on exception/interrupt, so the ordered leaf-apply thread
            // cannot hang waiting for a receiver that has already died.
            receiveTasksDone.countDown();
            logger.info(
                    RECONNECT.getMarker(),
                    "Learner receive breakdown: responses={} wallMs={} readBlockedMs={} parseMs={} "
                            + "applyMs={} maxApplyUs={} slowApplies={}",
                    responseCount,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos),
                    TimeUnit.NANOSECONDS.toMillis(readBlockedNanos),
                    TimeUnit.NANOSECONDS.toMillis(parseNanos),
                    TimeUnit.NANOSECONDS.toMillis(applyNanos),
                    TimeUnit.NANOSECONDS.toMicros(maxApplyNanos),
                    slowApplyCount);
        }
    }
}
