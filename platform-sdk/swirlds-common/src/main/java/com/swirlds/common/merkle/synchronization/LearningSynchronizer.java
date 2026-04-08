// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.synchronization;

import static com.swirlds.logging.legacy.LogMarker.RECONNECT;

import com.swirlds.common.merkle.synchronization.streams.AsyncInputStream;
import com.swirlds.common.merkle.synchronization.streams.AsyncOutputStream;
import com.swirlds.common.merkle.synchronization.utility.MerkleSynchronizationException;
import com.swirlds.common.merkle.synchronization.views.LearnerTreeView;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.io.streams.SerializableDataInputStream;
import org.hiero.base.io.streams.SerializableDataOutputStream;
import org.hiero.consensus.concurrent.manager.ThreadManager;
import org.hiero.consensus.concurrent.pool.StandardWorkGroup;
import org.hiero.consensus.reconnect.config.ReconnectConfig;

/**
 * Performs synchronization in the role of the learner.
 */
public class LearningSynchronizer {

    private static final String WORK_GROUP_NAME = "learning-synchronizer";

    private static final Logger logger = LogManager.getLogger(LearningSynchronizer.class);

    private final StandardWorkGroup workGroup;

    private final AtomicReference<Throwable> firstReconnectException = new AtomicReference<>();

    /**
     * Used to get data from the teacher.
     */
    private final SerializableDataInputStream inputStream;

    /**
     * Used to transmit data to the teacher.
     */
    private final SerializableDataOutputStream outputStream;

    /**
     * Virtual tree view used to access nodes and hashes.
     */
    private final LearnerTreeView view;

    private final ReconnectConfig reconnectConfig;

    /**
     * Create a new learning synchronizer.
     *
     * @param threadManager   responsible for managing thread lifecycles
     * @param in              the input stream
     * @param out             the output stream
     * @param view            the learner tree view
     * @param breakConnection a method that breaks the connection. Used iff an exception is encountered. Prevents
     *                        deadlock if there is a thread stuck on a blocking IO operation that will never finish due
     *                        to a failure.
     * @param reconnectConfig the configuration for the reconnect
     */
    public LearningSynchronizer(
            @NonNull final ThreadManager threadManager,
            @NonNull final SerializableDataInputStream in,
            @NonNull final SerializableDataOutputStream out,
            @NonNull final LearnerTreeView view,
            @NonNull final Runnable breakConnection,
            @NonNull final ReconnectConfig reconnectConfig) {
        inputStream = Objects.requireNonNull(in, "inputStream is null");
        outputStream = Objects.requireNonNull(out, "outputStream is null");
        this.reconnectConfig = Objects.requireNonNull(reconnectConfig, "reconnectConfig is null");

        this.view = Objects.requireNonNull(view, "view is null");

        final Function<Throwable, Boolean> reconnectExceptionListener = ex -> {
            firstReconnectException.compareAndSet(null, ex);
            return false;
        };
        workGroup = createStandardWorkGroup(threadManager, breakConnection, reconnectExceptionListener);
    }

    /**
     * Perform synchronization in the role of the learner.
     */
    public void synchronize() throws InterruptedException {
        logger.info(RECONNECT.getMarker(), "learner calls receiveTree()");
        receiveTree();
        logger.info(RECONNECT.getMarker(), "learner is done synchronizing");
    }

    /**
     * Receive a tree (or subtree) from the teacher
     */
    private void receiveTree() throws InterruptedException {
        final AsyncInputStream in = new AsyncInputStream(inputStream, workGroup, reconnectConfig);
        in.start();
        final AsyncOutputStream out = buildOutputStream(workGroup, outputStream, reconnectConfig);
        out.start();

        InterruptedException interruptException = null;
        try {
            view.startLearnerTasks(workGroup, in, out);
            workGroup.waitForTermination();
        } catch (final InterruptedException e) { // NOSONAR: Exception is rethrown below after cleanup.
            interruptException = e;
            logger.warn(RECONNECT.getMarker(), "Interrupted while waiting for work group termination");
        } catch (final Throwable t) {
            logger.info(RECONNECT.getMarker(), "Caught exception while receiving tree", t);
            throw new RuntimeException(t);
        }

        if (interruptException != null || workGroup.hasExceptions()) {
            in.abort();
            if (interruptException != null) {
                throw interruptException;
            }
            throw new MerkleSynchronizationException(
                    "Synchronization failed with exceptions", firstReconnectException.get());
        } else {
            view.onSuccessfulComplete();
        }

        logger.info(RECONNECT.getMarker(), "Finished receiving tree");
    }

    protected StandardWorkGroup createStandardWorkGroup(
            ThreadManager threadManager,
            Runnable breakConnection,
            Function<Throwable, Boolean> reconnectExceptionListener) {
        return new StandardWorkGroup(threadManager, WORK_GROUP_NAME, breakConnection, reconnectExceptionListener);
    }

    /**
     * Build the output stream. Exposed to allow unit tests to override implementation to simulate latency.
     */
    protected AsyncOutputStream buildOutputStream(
            @NonNull final StandardWorkGroup workGroup,
            @NonNull final SerializableDataOutputStream out,
            @NonNull final ReconnectConfig reconnectConfig) {
        return new AsyncOutputStream(out, workGroup, reconnectConfig);
    }
}
