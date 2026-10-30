// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.reconnect;

import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.swirlds.virtualmap.sync.LearnerTreeExchanger;
import com.swirlds.virtualmap.sync.streams.AsyncInputStream;
import com.swirlds.virtualmap.sync.streams.YieldStrategy;
import java.util.concurrent.CountDownLatch;

/**
 * A task running on the learner side, which is responsible for getting responses from the teacher.
 * <p>
 * This tasks terminates either on exception or when no more messages are provided by {@link AsyncInputStream}.
 * <p>
 * For every response from the teacher, the learner view is notified, which in turn notifies
 * the current traversal order, so it can recalculate the next virtual path to request.
 */
public class LearnerPullVirtualTreeReceiveTask implements Runnable {

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
        try {
            while (!Thread.currentThread().isInterrupted()) {
                final byte[] responseBytes = in.readOrWait(YieldStrategy.SLEEP);
                if (responseBytes == null) {
                    break;
                }
                final PullVirtualTreeResponse response =
                        PullVirtualTreeResponse.parseFrom(BufferedData.wrap(responseBytes));
                if (response.path() < 0) {
                    throw new IllegalStateException("Invalid path received from learner: " + response.path());
                }
                treeExchanger.responseReceived(response);
            }
        } finally {
            // Always signal completion, even on exception/interrupt, so the ordered leaf-apply thread
            // cannot hang waiting for a receiver that has already died.
            receiveTasksDone.countDown();
        }
    }
}
