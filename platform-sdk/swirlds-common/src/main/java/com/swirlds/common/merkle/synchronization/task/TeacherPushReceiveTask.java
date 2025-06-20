// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.synchronization.task;

import static com.swirlds.logging.legacy.LogMarker.RECONNECT;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import com.swirlds.common.merkle.synchronization.streams.AsyncInputStream;
import com.swirlds.common.merkle.synchronization.utility.MerkleSynchronizationException;
import com.swirlds.common.merkle.synchronization.views.TeacherTreeView;
import com.swirlds.common.threading.pool.StandardWorkGroup;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * This class encapsulates all logic for the teacher's receiving task.
 *
 * @param <T>
 * 		the type of data used by the view to represent a node
 */
public class TeacherPushReceiveTask<T> {

    private static final Logger logger = LogManager.getLogger(TeacherPushReceiveTask.class);

    private static final String NAME = "teacher-receive-task";

    private final StandardWorkGroup workGroup;
    private final AsyncInputStream<QueryResponse> in;
    private final TeacherTreeView<T> view;
    private final AtomicBoolean senderIsFinished;

    /**
     * Create a thread for receiving responses to queries from the learner.
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
    public TeacherPushReceiveTask(
            final StandardWorkGroup workGroup,
            final AsyncInputStream<QueryResponse> in,
            final TeacherTreeView<T> view,
            final AtomicBoolean senderIsFinished) {
        this.workGroup = workGroup;
        this.in = in;
        this.view = view;
        this.senderIsFinished = senderIsFinished;
    }

    public void start() {
        workGroup.execute(NAME, this::run);
    }

    private void run() {
        try (in) {
            boolean finished = senderIsFinished.get();
            boolean responseExpected = view.isResponseExpected();

            while (!finished || responseExpected) {
                if (responseExpected) {
                    final QueryResponse response = in.readAnticipatedMessage();
                    final T node = view.getNodeForNextResponse();
                    view.registerResponseForNode(node, response.doesLearnerHaveTheNode());
                } else {
                    MILLISECONDS.sleep(1);
                }

                finished = senderIsFinished.get();
                responseExpected = view.isResponseExpected();
            }
        } catch (final InterruptedException ex) {
            logger.warn(RECONNECT.getMarker(), "teacher's receiving thread interrupted");
            Thread.currentThread().interrupt();
        } catch (final Exception ex) {
            throw new MerkleSynchronizationException("exception in the teacher's receiving thread", ex);
        }
    }
}
