// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.reconnect;

import static com.swirlds.logging.legacy.LogMarker.RECONNECT;

import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.swirlds.virtualmap.internal.Path;
import com.swirlds.virtualmap.sync.LearnerTreeExchanger;
import com.swirlds.virtualmap.sync.streams.AsyncOutputStream;
import java.util.concurrent.CountDownLatch;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A task running on the learner side, which is responsible for sending requests to the teacher.
 *
 * <p>Before these tasks are started, the root node (path 0) request/response exchange is
 * performed synchronously by {@link com.swirlds.virtualmap.sync.LearningSynchronizer}, so the
 * traversal order is already fully initialized when this task begins.
 *
 * <p>This tasks terminates either on exception or when no path is available to send (when {@link Path#INVALID_PATH} is returned by the exchanger).
 */
public class LearnerPullVirtualTreeSendTask implements Runnable {

    private static final Logger logger = LogManager.getLogger(LearnerPullVirtualTreeSendTask.class);

    private final AsyncOutputStream out;
    private final LearnerTreeExchanger treeExchanger;

    private final CountDownLatch tasksDone;

    /**
     * Create a thread for sending node requests to the teacher.
     *
     * @param out
     * 		the output stream, this object is responsible for closing this when finished
     * @param treeExchanger
     * 		the exchanger used to determine what to send to the teacher
     * @param tasksDone
     *      the counter to decrease when this task is finished
     */
    public LearnerPullVirtualTreeSendTask(
            final AsyncOutputStream out, final LearnerTreeExchanger treeExchanger, final CountDownLatch tasksDone) {
        this.out = out;
        this.treeExchanger = treeExchanger;
        this.tasksDone = tasksDone;
    }

    /**
     * Main loop for the sender thread. Continuously queries the view for the next path to
     * request. Task finishes when all paths are exhausted ({@link Path#INVALID_PATH} is returned).
     */
    @Override
    public void run() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                final long path = treeExchanger.getNextPathToSend();
                if (path == Path.INVALID_PATH) {
                    break;
                }
                if (path < 0) {
                    assert path == NodeTraversalOrder.PATH_NOT_AVAILABLE_YET;
                    // No path available to send yet. Slow down
                    Thread.sleep(0, 1);
                    continue;
                }
                sendRequest(new PullVirtualTreeRequest(path, treeExchanger.getNodeHash(path)));
                treeExchanger.getMapStats().incrementTransfersFromLearner();
            }
        } catch (final InterruptedException ex) {
            logger.warn(RECONNECT.getMarker(), "Learner sending task is interrupted");
            Thread.currentThread().interrupt();
        } finally {
            tasksDone.countDown();
        }
    }

    /**
     * Serializes the given request to bytes and sends it through the async output stream.
     *
     * @param request the request to send
     * @throws InterruptedException if interrupted while waiting to enqueue
     */
    private void sendRequest(final PullVirtualTreeRequest request) throws InterruptedException {
        final byte[] bytes = new byte[request.getSizeInBytes()];
        request.writeTo(BufferedData.wrap(bytes));
        out.sendAsync(bytes);
    }
}
