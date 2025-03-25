// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.reconnect;

import static com.swirlds.logging.legacy.LogMarker.RECONNECT;

import com.swirlds.common.merkle.synchronization.utility.MerkleSynchronizationException;
import com.swirlds.common.threading.pool.StandardWorkGroup;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.io.streams.SerializableDataInputStream;

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
    private final SerializableDataInputStream in;
    private final LearnerPullVirtualTreeView view;

    // Indicates if the learner sender task is done sending all requests to the teacher
    private final AtomicBoolean senderIsFinished;

    // Number of requests sent to teacher / responses expected from the teacher. Increased in
    // the sending task, decreased in this task
    private final AtomicLong expectedResponses;

    // Indicates if a response for path 0 (virtual root node) has been received
    private final CountDownLatch rootResponseReceived;

    /**
     * Create a thread for receiving responses to queries from the teacher.
     *
     * @param workGroup
     * 		the work group that will manage this thread
     * @param in
     * 		the input stream, this object is responsible for closing this when finished
     * @param view
     * 		the view to be used when touching the merkle tree
     * @param senderIsFinished
     * 		becomes true once the sending thread has finished
     */
    public LearnerPullVirtualTreeReceiveTask(
            final StandardWorkGroup workGroup,
            final SerializableDataInputStream in,
            final LearnerPullVirtualTreeView view,
            final AtomicBoolean senderIsFinished,
            final AtomicLong expectedResponses,
            final CountDownLatch rootResponseReceived) {
        this.workGroup = workGroup;
        this.in = in;
        this.view = view;
        this.senderIsFinished = senderIsFinished;
        this.expectedResponses = expectedResponses;
        this.rootResponseReceived = rootResponseReceived;
    }

    public void exec() {
        workGroup.execute(NAME, this::run);
    }

    private void run() {
        try (view) {
            boolean finished = senderIsFinished.get();
            boolean responseExpected = expectedResponses.get() > 0;

            while (!finished || responseExpected) {
                if (responseExpected) {
                    final PullVirtualTreeResponse response = new PullVirtualTreeResponse(view);
                    // the learner tree is notified about the new response in deserialize() method below
                    response.deserialize(in, 0);
                    view.getMapStats().incrementTransfersFromTeacher();
                    logger.debug(RECONNECT.getMarker(), "Learner receive path: " + response.getPath());
                    if (response.getPath() == 0) {
                        rootResponseReceived.countDown();
                    }
                    expectedResponses.decrementAndGet();
                } else {
                    Thread.onSpinWait();
                }

                finished = senderIsFinished.get();
                responseExpected = expectedResponses.get() > 0;
            }
            logger.debug(RECONNECT.getMarker(), "Learner receive done");
        } catch (final Exception ex) {
            throw new MerkleSynchronizationException("Exception in the learner's receiving task", ex);
        }
    }
}
