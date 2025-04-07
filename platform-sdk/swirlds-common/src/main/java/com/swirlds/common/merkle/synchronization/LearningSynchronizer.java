// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.synchronization;

import static com.swirlds.base.units.UnitConstants.MILLISECONDS_TO_SECONDS;
import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.RECONNECT;

import com.swirlds.common.io.streams.MerkleDataInputStream;
import com.swirlds.common.io.streams.MerkleDataOutputStream;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.crypto.MerkleCryptography;
import com.swirlds.common.merkle.synchronization.config.ReconnectConfig;
import com.swirlds.common.merkle.synchronization.stats.ReconnectMapMetrics;
import com.swirlds.common.merkle.synchronization.stats.ReconnectMapStats;
import com.swirlds.common.merkle.synchronization.streams.AsyncOutputStream;
import com.swirlds.common.merkle.synchronization.task.ReconnectNodeCount;
import com.swirlds.common.merkle.synchronization.utility.MerkleSynchronizationException;
import com.swirlds.common.merkle.synchronization.views.CustomReconnectRoot;
import com.swirlds.common.merkle.synchronization.views.LearnerPushMerkleTreeView;
import com.swirlds.common.merkle.synchronization.views.LearnerTreeView;
import com.swirlds.common.merkle.utility.MerkleTreeVisualizer;
import com.swirlds.common.threading.manager.ThreadManager;
import com.swirlds.common.threading.pool.StandardWorkGroup;
import com.swirlds.logging.legacy.payload.SynchronizationCompletePayload;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Deque;
import java.util.LinkedList;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.io.SelfSerializable;
import org.hiero.consensus.model.io.streams.SerializableDataOutputStream;

/**
 * Performs synchronization in the role of the learner.
 */
public class LearningSynchronizer implements ReconnectNodeCount {

    private static final String WORK_GROUP_NAME = "learning-synchronizer";

    private static final Logger logger = LogManager.getLogger(LearningSynchronizer.class);

    /**
     * Used to get data from the teacher.
     */
    private final MerkleDataInputStream inputStream;

    /**
     * Used to transmit data to the teacher.
     */
    private final MerkleDataOutputStream outputStream;

    private final Queue<MerkleNode> rootsToReceive;
    private final Deque<LearnerTreeView<?>> viewsToInitialize;
    private final Runnable breakConnection;
    /**
     * The root of the merkle tree that resulted from the synchronization operation.
     */
    private MerkleNode newRoot;

    private int leafNodesReceived;
    private int internalNodesReceived;
    private int redundantLeafNodes;
    private int redundantInternalNodes;

    private long synchronizationTimeMilliseconds;
    private long hashTimeMilliseconds;
    private long initializationTimeMilliseconds;

    private final MerkleCryptography merkleCryptography;

    protected final ReconnectConfig reconnectConfig;

    /**
     * Responsible for creating and managing threads used by this object.
     */
    private final ThreadManager threadManager;

    private final ReconnectMapStats mapStats;

    /**
     * Create a new learning synchronizer.
     *
     * @param threadManager   responsible for managing thread lifecycles
     * @param in              the input stream
     * @param out             the output stream
     * @param root            the root of the tree
     * @param breakConnection a method that breaks the connection. Used iff an exception is encountered. Prevents
     *                        deadlock if there is a thread stuck on a blocking IO operation that will never finish due
     *                        to a failure.
     * @param reconnectConfig the configuration for the reconnect
     * @param metrics         a Metrics instance for ReconnectMapStats
     */
    public LearningSynchronizer(
            @NonNull final ThreadManager threadManager,
            @NonNull final MerkleDataInputStream in,
            @NonNull final MerkleDataOutputStream out,
            @NonNull final MerkleNode root,
            @NonNull final Runnable breakConnection,
            @NonNull final MerkleCryptography merkleCryptography,
            @NonNull final ReconnectConfig reconnectConfig,
            @NonNull final Metrics metrics) {

        this.threadManager = Objects.requireNonNull(threadManager, "threadManager is null");

        inputStream = Objects.requireNonNull(in, "inputStream is null");
        outputStream = Objects.requireNonNull(out, "outputStream is null");
        this.merkleCryptography = Objects.requireNonNull(merkleCryptography, "merkleCryptography is null");
        this.reconnectConfig = Objects.requireNonNull(reconnectConfig, "reconnectConfig is null");

        rootsToReceive = new LinkedList<>();
        viewsToInitialize = new LinkedList<>();
        rootsToReceive.add(root);

        this.breakConnection = breakConnection;

        this.mapStats = new ReconnectMapMetrics(metrics, null, null);
    }

    /**
     * Perform synchronization in the role of the learner.
     */
    public void synchronize() throws InterruptedException {
        try {
            logger.info(RECONNECT.getMarker(), "learner calls receiveTree()");
            receiveTree();
            logger.info(RECONNECT.getMarker(), "learner calls initialize()");
            initialize();
            logger.info(RECONNECT.getMarker(), "learner calls hash()");
            hash();
            logger.info(RECONNECT.getMarker(), "learner calls logStatistics()");
            logStatistics();
            logger.info(RECONNECT.getMarker(), "learner is done synchronizing");
        } catch (final InterruptedException ex) {
            logger.warn(RECONNECT.getMarker(), "synchronization interrupted");
            Thread.currentThread().interrupt();
            abort();
            throw ex;
        } catch (final Exception ex) {
            abort();
            throw new MerkleSynchronizationException(ex);
        }
    }

    /**
     * Attempt to free any and all resources that were acquired during the reconnect attempt.
     */
    private void abort() {
        logger.warn(
                RECONNECT.getMarker(),
                "Deleting partially constructed tree:\n{}",
                new MerkleTreeVisualizer(newRoot)
                        .setDepth(5)
                        .setUseHashes(false)
                        .setUseMnemonics(false)
                        .render());
        try {
            if (newRoot != null) {
                newRoot.release();
            }
        } catch (final Exception ex) {
            // The tree may be in a partially constructed state. We don't expect exceptions, but they
            // may be more likely to appear during this operation than at other times.
            logger.error(EXCEPTION.getMarker(), "exception thrown while releasing tree", ex);
        }
    }

    /**
     * Receive the tree from the teacher.
     */
    private void receiveTree() throws InterruptedException {
        logger.info(RECONNECT.getMarker(), "synchronizing tree");
        final long start = System.currentTimeMillis();

        while (!rootsToReceive.isEmpty()) {
            final MerkleNode root = receiveTree(rootsToReceive.remove());
            if (newRoot == null) {
                // The first tree synchronized will contain the root of the tree as a whole
                newRoot = root;
            }
        }

        synchronizationTimeMilliseconds = System.currentTimeMillis() - start;
        logger.info(RECONNECT.getMarker(), "synchronization complete");
    }

    /**
     * Initialize the tree.
     */
    private void initialize() {
        logger.info(RECONNECT.getMarker(), "initializing tree");
        final long start = System.currentTimeMillis();

        while (!viewsToInitialize.isEmpty()) {
            viewsToInitialize.removeFirst().initialize();
        }

        initializationTimeMilliseconds = System.currentTimeMillis() - start;
        logger.info(RECONNECT.getMarker(), "initialization complete");
    }

    /**
     * Hash the tree.
     */
    private void hash() throws InterruptedException {
        logger.info(RECONNECT.getMarker(), "hashing tree");
        final long start = System.currentTimeMillis();

        try {
            merkleCryptography.digestTreeAsync(newRoot).get();
        } catch (ExecutionException e) {
            logger.error(EXCEPTION.getMarker(), "exception while computing hash of reconstructed tree", e);
            return;
        }

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
                .setInitializationTimeInSeconds(initializationTimeMilliseconds * MILLISECONDS_TO_SECONDS)
                .setTotalNodes(leafNodesReceived + internalNodesReceived)
                .setLeafNodes(leafNodesReceived)
                .setRedundantLeafNodes(redundantLeafNodes)
                .setInternalNodes(internalNodesReceived)
                .setRedundantInternalNodes(redundantInternalNodes)
                .toString());
        logger.info(RECONNECT.getMarker(), () -> mapStats.format());
    }

    /**
     * Get the root of the resulting tree. May return an incomplete tree if called before synchronization is finished.
     */
    public MerkleNode getRoot() {
        return newRoot;
    }

    /**
     * Receive a tree (or subtree) from the teacher
     *
     * @param root the root of the tree (or subtree) that is already possessed
     * @return the root of the reconstructed tree
     */
    @SuppressWarnings("unchecked")
    private <T> MerkleNode receiveTree(final MerkleNode root) throws InterruptedException {

        logger.info(
                RECONNECT.getMarker(),
                "receiving tree rooted at {} with route {}",
                root == null ? "(unknown)" : root.getClass().getName(),
                root == null ? "[]" : root.getRoute());

        final AtomicReference<Throwable> firstReconnectException = new AtomicReference<>();
        final Function<Throwable, Boolean> reconnectExceptionListener = t -> {
            firstReconnectException.compareAndSet(null, t);
            return false;
        };
        final StandardWorkGroup workGroup =
                createStandardWorkGroup(threadManager, breakConnection, reconnectExceptionListener);

        final LearnerTreeView<T> view;
        if (root == null || !root.hasCustomReconnectView()) {
            view = (LearnerTreeView<T>) new LearnerPushMerkleTreeView(reconnectConfig, root, mapStats);
        } else {
            assert root instanceof CustomReconnectRoot;
            view = ((CustomReconnectRoot<?, T>) root).buildLearnerView(reconnectConfig, mapStats);
        }

        final AtomicReference<T> reconstructedRoot = new AtomicReference<>();

        view.startLearnerTasks(this, workGroup, inputStream, outputStream, rootsToReceive, reconstructedRoot);
        InterruptedException interruptException = null;
        try {
            workGroup.waitForTermination();

            logger.info(
                    RECONNECT.getMarker(),
                    "received tree rooted at {} with route {}",
                    root == null ? "(unknown)" : root.getClass().getName(),
                    root == null ? "[]" : root.getRoute());
        } catch (final InterruptedException e) { // NOSONAR: Exception is rethrown below after cleanup.
            interruptException = e;
            logger.warn(RECONNECT.getMarker(), "interrupted while waiting for work group termination");
        } catch (final Throwable t) {
            logger.info(
                    RECONNECT.getMarker(),
                    "caught exception while receiving tree rooted at {} with route {}",
                    root == null ? "(unknown)" : root.getClass().getName(),
                    root == null ? "[]" : root.getRoute(),
                    t);
            throw t;
        }

        if (interruptException != null || workGroup.hasExceptions()) {

            // Depending on where the failure occurred, there may be deserialized objects still sitting in
            // the async input stream's queue that haven't been attached to any tree.
            view.abort();

            final MerkleNode merkleRoot = view.getMerkleRoot(reconstructedRoot.get());
            if (merkleRoot != null && merkleRoot.getReservationCount() == 0) {
                // If the root has a reference count of 0 then it is not underneath any other tree,
                // and this thread holds the implicit reference to the root.
                // This is the last chance to release that tree in this scenario.
                logger.warn(RECONNECT.getMarker(), "deleting partially constructed subtree");
                merkleRoot.release();
            }
            if (interruptException != null) {
                throw interruptException;
            }
            throw new MerkleSynchronizationException(
                    "Synchronization failed with exceptions", firstReconnectException.get());
        }

        viewsToInitialize.addFirst(view);

        return view.getMerkleRoot(reconstructedRoot.get());
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
