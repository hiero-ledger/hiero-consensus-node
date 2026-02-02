// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.synchronization;

import static com.swirlds.base.units.UnitConstants.MILLISECONDS_TO_SECONDS;
import static com.swirlds.logging.legacy.LogMarker.RECONNECT;

import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.synchronization.streams.AsyncOutputStream;
import com.swirlds.common.merkle.synchronization.task.ReconnectNodeCount;
import com.swirlds.common.merkle.synchronization.utility.MerkleSynchronizationException;
import com.swirlds.common.merkle.synchronization.views.LearnerTreeView;
import com.swirlds.logging.legacy.payload.SynchronizationCompletePayload;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.io.SelfSerializable;
import org.hiero.base.io.streams.SerializableDataInputStream;
import org.hiero.base.io.streams.SerializableDataOutputStream;
import org.hiero.consensus.concurrent.manager.ThreadManager;
import org.hiero.consensus.concurrent.pool.StandardWorkGroup;
import org.hiero.consensus.reconnect.config.ReconnectConfig;

/**
 * Performs synchronization in the role of the learner.
 */
public class LearningSynchronizer implements ReconnectNodeCount {

    private static final String WORK_GROUP_NAME = "learning-synchronizer";

    private static final Logger logger = LogManager.getLogger(LearningSynchronizer.class);

    /**
     * Used to get data from the teacher.
     */
    private final SerializableDataInputStream inputStream;

    /**
     * Used to transmit data to the teacher.
     */
    private final SerializableDataOutputStream outputStream;

    private final Runnable breakConnection;

    /**
     * New state root node used to put data from the teacher. The node is always a
     * virtual map. FUTURE WORK: move this code from swirlds-common to a different
     * module that may depend on swirlds-virtualmap and change newRoot type from
     * MerkleNode to VirtualMap.
     */
    private final MerkleNode newRoot;

    /**
     * Virtual tree view used to access nodes and hashes in the newRoot above.
     */
    private final LearnerTreeView<?> view;

    private int leafNodesReceived;
    private int internalNodesReceived;
    private int redundantLeafNodes;
    private int redundantInternalNodes;

    private long synchronizationTimeMilliseconds;
    private long hashTimeMilliseconds;

    protected final ReconnectConfig reconnectConfig;

    /**
     * Responsible for creating and managing threads used by this object.
     */
    private final ThreadManager threadManager;

    /**
     * Create a new learning synchronizer.
     *
     * @param threadManager   responsible for managing thread lifecycles
     * @param in              the input stream
     * @param out             the output stream
     * @param newRoot         the merkle root, which will be used to reconstruct the merkle tree
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
            @NonNull final MerkleNode newRoot,
            @NonNull final LearnerTreeView<?> view,
            @NonNull final Runnable breakConnection,
            @NonNull final ReconnectConfig reconnectConfig) {

        this.threadManager = Objects.requireNonNull(threadManager, "threadManager is null");

        inputStream = Objects.requireNonNull(in, "inputStream is null");
        outputStream = Objects.requireNonNull(out, "outputStream is null");
        this.reconnectConfig = Objects.requireNonNull(reconnectConfig, "reconnectConfig is null");

        this.newRoot = Objects.requireNonNull(newRoot, "newRoot is null");
        this.view = Objects.requireNonNull(view, "view is null");

        this.breakConnection = breakConnection;
    }

    /**
     * Perform synchronization in the role of the learner.
     */
    public void synchronize() throws InterruptedException {
        logger.info(RECONNECT.getMarker(), "learner calls receiveTree()");
        final long syncStartTime = System.currentTimeMillis();
        receiveTree();
        synchronizationTimeMilliseconds = System.currentTimeMillis() - syncStartTime;
        logger.info(RECONNECT.getMarker(), "learner calls hash()");
        final long hashStartTime = System.currentTimeMillis();
        hash();
        hashTimeMilliseconds = System.currentTimeMillis() - hashStartTime;
        logger.info(RECONNECT.getMarker(), "learner calls logStatistics()");
        logStatistics();
        logger.info(RECONNECT.getMarker(), "learner is done synchronizing");
    }

    /**
     * Hash the tree.
     */
    private void hash() throws InterruptedException {
        logger.info(RECONNECT.getMarker(), "hashing tree");
        final long start = System.currentTimeMillis();

        newRoot.getHash(); // calculate hash

        hashTimeMilliseconds = System.currentTimeMillis() - start;
        logger.info(RECONNECT.getMarker(), "hashing complete");
    }

    /**
     * Log information about the synchronization.
     */
    private void logStatistics() {
        logger.info(RECONNECT.getMarker(), () -> new SynchronizationCompletePayload("Finished synchronization")
                .setTimeInSeconds(synchronizationTimeMilliseconds * MILLISECONDS_TO_SECONDS)
                .setHashTimeInSeconds(hashTimeMilliseconds * MILLISECONDS_TO_SECONDS)
                .setTotalNodes(leafNodesReceived + internalNodesReceived)
                .setLeafNodes(leafNodesReceived)
                .setRedundantLeafNodes(redundantLeafNodes)
                .setInternalNodes(internalNodesReceived)
                .setRedundantInternalNodes(redundantInternalNodes)
                .toString());
    }

    /**
     * Receive a tree (or subtree) from the teacher
     */
    private void receiveTree() throws InterruptedException {
        final AtomicReference<Throwable> firstReconnectException = new AtomicReference<>();
        final Function<Throwable, Boolean> reconnectExceptionListener = t -> {
            firstReconnectException.compareAndSet(null, t);
            return false;
        };
        final StandardWorkGroup workGroup =
                createStandardWorkGroup(threadManager, breakConnection, reconnectExceptionListener);

        view.startLearnerTasks(this, workGroup, inputStream, outputStream);
        InterruptedException interruptException = null;
        try {
            workGroup.waitForTermination();
        } catch (final InterruptedException e) { // NOSONAR: Exception is rethrown below after cleanup.
            interruptException = e;
            logger.warn(RECONNECT.getMarker(), "interrupted while waiting for work group termination");
        } catch (final Throwable t) {
            logger.info(RECONNECT.getMarker(), "caught exception while receiving tree", t);
            throw t;
        }

        if (interruptException != null || workGroup.hasExceptions()) {
            // Depending on where the failure occurred, there may be deserialized objects still sitting in
            // the async input stream's queue that haven't been attached to any tree.
            view.abort();
            if (interruptException != null) {
                throw interruptException;
            }
            throw new MerkleSynchronizationException(
                    "Synchronization failed with exceptions", firstReconnectException.get());
        }
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
    public <T extends SelfSerializable> AsyncOutputStream<T> buildOutputStream(
            final StandardWorkGroup workGroup, final SerializableDataOutputStream out) {
        return new AsyncOutputStream<>(out, workGroup, reconnectConfig);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void incrementLeafCount() {
        leafNodesReceived++;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void incrementRedundantLeafCount() {
        redundantLeafNodes++;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void incrementInternalCount() {
        internalNodesReceived++;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void incrementRedundantInternalCount() {
        redundantInternalNodes++;
    }
}
