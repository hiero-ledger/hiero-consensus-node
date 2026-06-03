// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.reconnect;

import static com.swirlds.logging.legacy.LogMarker.RECONNECT;

import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.swirlds.virtualmap.internal.Path;
import com.swirlds.virtualmap.sync.LearnerTreeView;
import com.swirlds.virtualmap.sync.streams.AsyncOutputStream;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.concurrent.pool.StandardWorkGroup;

/**
 * A task running on the learner side, which is responsible for sending requests to the teacher.
 *
 * <p>Before these tasks are started, the root node (path 0) request/response exchange is
 * performed synchronously by {@link com.swirlds.virtualmap.sync.LearningSynchronizer}, so the
 * traversal order is already fully initialized when this task begins.
 *
 * <p>This task keeps sending requests according to the provided {@link NodeTraversalOrder}.
 * When the next path to request is {@link Path#INVALID_PATH}, a terminating request is sent to
 * the teacher to signal no more requests will follow, and this task finishes.
 */
public class LearnerPullVirtualTreeSendTask {

    private static final Logger logger = LogManager.getLogger(LearnerPullVirtualTreeSendTask.class);

    private static final String NAME = "reconnect-learner-sender";

    private final StandardWorkGroup workGroup;
    private final AsyncOutputStream out;
    private final LearnerTreeView view;

    // Number of requests sent to teacher / responses expected from the teacher. Increased in
    // this task, decreased in the receiving task
    private final AtomicLong responsesExpected;

    private final AtomicInteger tasksDone;

    /**
     * Create a thread for sending node requests to the teacher.
     *
     * @param workGroup
     * 		the work group that will manage this thread
     * @param out
     * 		the output stream, this object is responsible for closing this when finished
     * @param view
     * 		the view to be used when touching the merkle tree
     * @param responsesExpected
     *      number of responses expected from the teacher, increased by one every time a request
     *      is sent
     * @param tasksDone
     *      the counter to decrease when this task is finished
     */
    public LearnerPullVirtualTreeSendTask(
            final StandardWorkGroup workGroup,
            final AsyncOutputStream out,
            final LearnerTreeView view,
            final AtomicLong responsesExpected,
            final AtomicInteger tasksDone) {
        this.workGroup = workGroup;
        this.out = out;
        this.view = view;
        this.responsesExpected = responsesExpected;
        this.tasksDone = tasksDone;
    }

    /**
     * Start the background thread that sends requests to the teacher.
     */
    public void exec() {
        workGroup.execute(NAME, this::run);
    }

    /**
     * Main loop for the sender thread. Continuously queries the view for the next path to
     * request. When all paths are exhausted ({@link Path#INVALID_PATH} is returned), sends
     * a terminating request to the teacher and signals the async output stream to finish.
     */
    private void run() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                final long path = view.getNextPathToSend();
                if (path == Path.INVALID_PATH) {
                    // Once the last learner sending task is done, send the teacher a marker
                    // (final) reconnect request and terminate the async out
                    if (tasksDone.decrementAndGet() == 0) {
                        sendRequest(new PullVirtualTreeRequest(Path.INVALID_PATH, null));
                        out.done();
                    }
                    break;
                }
                if (path < 0) {
                    assert path == NodeTraversalOrder.PATH_NOT_AVAILABLE_YET;
                    // No path available to send yet. Slow down
                    Thread.sleep(0, 1);
                    continue;
                }
                sendRequest(new PullVirtualTreeRequest(path, view.getNodeHash(path)));
                responsesExpected.incrementAndGet();
                view.getMapStats().incrementTransfersFromLearner();
            }
        } catch (final InterruptedException ex) {
            logger.warn(RECONNECT.getMarker(), "Learner sending task is interrupted");
            Thread.currentThread().interrupt();
        } catch (final Exception ex) {
            workGroup.handleError(ex);
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
