// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.synchronization;

import static com.swirlds.logging.legacy.LogMarker.RECONNECT;

import com.swirlds.common.merkle.synchronization.streams.AsyncInputStream;
import com.swirlds.common.merkle.synchronization.streams.AsyncOutputStream;
import com.swirlds.common.merkle.synchronization.utility.MerkleSynchronizationException;
import com.swirlds.common.merkle.synchronization.views.LearnerTreeView;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.Hashable;
import org.hiero.consensus.concurrent.manager.ThreadManager;
import org.hiero.consensus.concurrent.pool.StandardWorkGroup;
import org.hiero.consensus.reconnect.config.ReconnectConfig;

/**
 * Performs synchronization in the role of the learner.
 *
 * @param <T> the type of a message received from the teacher
 */
public class LearningSynchronizer<T> {

    private static final Logger logger = LogManager.getLogger(LearningSynchronizer.class);

    private static final String WORK_GROUP_NAME = "reconnect-learner";

    /**
     * Used to get data from the teacher.
     */
    private final DataInputStream inputStream;

    /**
     * Used to transmit data to the teacher.
     */
    private final DataOutputStream outputStream;

    /**
     * New state root node used to put data from the teacher.
     */
    private final Hashable newRoot;

    /**
     * Virtual tree view used to access nodes and hashes in the newRoot above.
     */
    private final LearnerTreeView<T> view;

    private final ReconnectConfig reconnectConfig;
    private final StandardWorkGroup workGroup;
    private final AtomicReference<Throwable> firstReconnectException = new AtomicReference<>();

    /**
     * Constructs a new learning synchronizer.
     *
     * @param threadManager responsible for managing thread lifecycles
     * @param in the input stream for receiving data from the teacher
     * @param out the output stream for sending data to the teacher
     * @param newRoot the root node of the tree being reconstructed
     * @param view the learner's view into the merkle tree being synchronized
     * @param breakConnection a callback to disconnect the connection on failure
     * @param reconnectConfig the reconnect configuration
     */
    public LearningSynchronizer(
            @NonNull final ThreadManager threadManager,
            @NonNull final DataInputStream in,
            @NonNull final DataOutputStream out,
            @NonNull final Hashable newRoot,
            @NonNull final LearnerTreeView<T> view,
            @NonNull final Runnable breakConnection,
            @NonNull final ReconnectConfig reconnectConfig) {
        inputStream = Objects.requireNonNull(in, "inputStream is null");
        outputStream = Objects.requireNonNull(out, "outputStream is null");
        this.reconnectConfig = Objects.requireNonNull(reconnectConfig, "reconnectConfig is null");

        this.newRoot = Objects.requireNonNull(newRoot, "newRoot is null");
        this.view = Objects.requireNonNull(view, "view is null");

        final Function<Throwable, Boolean> reconnectExceptionListener = ex -> {
            firstReconnectException.compareAndSet(null, ex);
            return false;
        };
        workGroup = createStandardWorkGroup(threadManager, breakConnection, reconnectExceptionListener);
    }

    /**
     * Perform reconnect in the role of the learner.
     */
    public void synchronize() throws InterruptedException {
        logger.info(RECONNECT.getMarker(), "learner calls receiveTree()");
        receiveTree();
        logger.info(RECONNECT.getMarker(), "learner calls hash()");
        hash();
        logger.info(RECONNECT.getMarker(), "learner is done synchronizing");
    }

    /**
     * Compute the hash of the reconstructed tree.
     */
    private void hash() {
        newRoot.getHash();
    }

    /**
     * Receive the tree from the teacher by setting up async streams and delegating to the
     * learner view's tasks.
     *
     * @throws InterruptedException if the current thread is interrupted
     */
    private void receiveTree() throws InterruptedException {
        final AsyncInputStream<T> in =
                new AsyncInputStream<>(inputStream, workGroup, reconnectConfig, view.getInputParser());
        in.start();
        final AtomicBoolean teacherSentLastRequest = new AtomicBoolean(false);
        final AsyncOutputStream out = buildOutputStream(workGroup, outputStream, reconnectConfig);
        out.start();

        InterruptedException interruptException = null;
        try (view) {
            view.startLearnerTasks(workGroup, in, out, () -> teacherSentLastRequest.set(true));
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
            @NonNull final DataOutputStream out,
            @NonNull final ReconnectConfig reconnectConfig) {
        return new AsyncOutputStream(out, workGroup, reconnectConfig);
    }
}
