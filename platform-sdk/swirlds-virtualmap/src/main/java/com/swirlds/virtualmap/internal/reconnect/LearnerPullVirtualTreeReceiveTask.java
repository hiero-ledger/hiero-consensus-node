// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.reconnect;

import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.swirlds.virtualmap.internal.Path;
import com.swirlds.virtualmap.sync.LearnerTreeExchanger;
import com.swirlds.virtualmap.sync.streams.AsyncInputStream;
import com.swirlds.virtualmap.sync.streams.YieldStrategy;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import org.hiero.consensus.concurrent.pool.StandardWorkGroup;

/**
 * A task running on the learner side, which is responsible for getting responses from the teacher.
 * <p>
 * This tasks terminates either on exception or when no more messages are provided by {@link AsyncInputStream}.
 * <p>
 * For every response from the teacher, the learner view is notified, which in turn notifies
 * the current traversal order, so it can recalculate the next virtual path to request.
 */
public class LearnerPullVirtualTreeReceiveTask {

    private static final String NAME = "reconnect-learner-receiver";

    private final AsyncInputStream in;
    private final LearnerTreeExchanger treeExchanger;

    /**
     * Create a thread for receiving responses to queries from the teacher.
     *
     * @param in
     * 		the input stream, this object is responsible for closing this when finished
     * @param treeExchanger
     * 		the exchanger used to callback on tree node received
     */
    public LearnerPullVirtualTreeReceiveTask(final AsyncInputStream in, final LearnerTreeExchanger treeExchanger) {
        this.in = in;
        this.treeExchanger = treeExchanger;
    }

    /**
     * Start the background thread that receives responses from the teacher.
     */
    public void exec(final @NonNull StandardWorkGroup workGroup) {
        Objects.requireNonNull(workGroup, "workGroup must not be null");
        workGroup.execute(NAME, this::run);
    }

    /**
     * Main loop for the receiver thread. Reads responses from the async input stream,
     * tracks reconnect statistics, and delegates to the learner view.
     * Terminates when input streams returns no more messages to process.
     */
    private void run() {
        while (!Thread.currentThread().isInterrupted()) {
            final byte[] responseBytes = in.readOrWait(YieldStrategy.SLEEP);
            if (responseBytes == null) {
                break;
            }
            final PullVirtualTreeResponse response =
                    PullVirtualTreeResponse.parseFrom(BufferedData.wrap(responseBytes));

            assert response.path() != Path.INVALID_PATH : "Invalid path received from teacher: " + response.path();

            treeExchanger.responseReceived(response);
        }
    }
}
