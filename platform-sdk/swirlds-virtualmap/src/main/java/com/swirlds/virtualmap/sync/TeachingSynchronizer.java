// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.sync;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.RECONNECT;

import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.swirlds.config.api.Configuration;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.config.VirtualMapSyncConfig;
import com.swirlds.virtualmap.internal.Path;
import com.swirlds.virtualmap.internal.RecordAccessor;
import com.swirlds.virtualmap.internal.reconnect.PullVirtualTreeRequest;
import com.swirlds.virtualmap.internal.reconnect.PullVirtualTreeResponse;
import com.swirlds.virtualmap.internal.reconnect.TeacherPullVirtualTreeReceiveTask;
import com.swirlds.virtualmap.sync.streams.AsyncInputStream;
import com.swirlds.virtualmap.sync.streams.AsyncOutputStream;
import com.swirlds.virtualmap.sync.streams.YieldStrategy;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.concurrent.manager.ThreadManager;
import org.hiero.consensus.concurrent.pool.StandardWorkGroup;

/**
 * Performs reconnect in the role of the teacher.
 */
public class TeachingSynchronizer {

    private static final Logger logger = LogManager.getLogger(TeachingSynchronizer.class);

    private static final String WORK_GROUP_NAME = "reconnect-teacher";

    private final RecordAccessor teacherView;
    private final ThreadManager threadManager;
    private final VirtualMapSyncConfig syncConfig;

    /**
     * Constructs a new teaching synchronizer.
     *
     * @param teacherMap teacher virtual map that would be detached so it can be released after this instance is created
     * @param threadManager responsible for managing thread lifecycles
     * @param config the configuration
     */
    public TeachingSynchronizer(
            @NonNull final VirtualMap teacherMap,
            @NonNull final ThreadManager threadManager,
            @NonNull final Configuration config) {

        teacherView = Objects.requireNonNull(teacherMap, "teacher map is null").detach();
        this.threadManager = Objects.requireNonNull(threadManager, "threadManager is null");
        this.syncConfig = Objects.requireNonNull(config, "config is null").getConfigData(VirtualMapSyncConfig.class);
    }

    /**
     * Perform reconnect in the role of the teacher blocking until it's finished.
     *
     * @param in data input stream for reading requests from the learner
     * @param out data output stream for sending responses to the learner
     * @param breakConnection action to break the connection, which should be called if a reconnect-related exception is encountered and the connection should be closed.
     *
     * @throws InterruptedException if the thread is interrupted while waiting for tasks to complete
     * @throws MerkleSynchronizationException if any error occurs during synchronization
     */
    public void synchronize(
            @NonNull final DataInputStream in,
            @NonNull final DataOutputStream out,
            @NonNull final Runnable breakConnection)
            throws InterruptedException {
        Objects.requireNonNull(in, "input stream cannot be null");
        Objects.requireNonNull(out, "output stream cannot be null");
        Objects.requireNonNull(breakConnection, "break connection action cannot be null");

        try (final StandardWorkGroup workGroup = createStandardWorkGroup(threadManager, breakConnection)) {
            logger.info(RECONNECT.getMarker(), "teacher start synchronizing");

            final AsyncInputStream input =
                    new AsyncInputStream(in, syncConfig.asyncStreamBufferSize(), syncConfig.asyncStreamTimeout());
            input.start(workGroup);
            final AsyncOutputStream output = buildOutputStream(out, syncConfig);
            output.start(workGroup);

            // Perform the root-node (path 0) request/response handshake synchronously before forking
            // any parallel tasks. The root response carries the teacher's first/last leaf path range.
            // Once sent, the learner will start sending non-root requests, which the parallel tasks
            // will process. This guarantees no task races for the first message on the stream.

            exchangeRootNode(teacherView, input, output);

            // FUTURE work: pool size config
            final int teacherTasks = 16;
            final CountDownLatch tasksDone = new CountDownLatch(teacherTasks);
            for (int i = 0; i < teacherTasks; i++) {
                workGroup.fork(
                        "reconnect-teacher-receiver",
                        new TeacherPullVirtualTreeReceiveTask(input, output, teacherView, tasksDone));
            }

            // when all receive tasks done, output can be closed, which signals the learner that no more responses will
            // be sent.
            // This allows the learner to complete and close its input stream.
            try {
                tasksDone.await();
            } catch (final InterruptedException ie) {
                Thread.currentThread().interrupt();
            } finally {
                output.done(); // always signal the peer, even on interrupt
            }

            workGroup.join();
        } catch (final InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw ie;
        } catch (final Throwable t) {
            logger.info(RECONNECT.getMarker(), "Caught exception while synchronizing tree", t);
            throwCause(t);
        } finally {
            try {
                teacherView.close();
            } catch (IOException e) {
                logger.error(EXCEPTION.getMarker(), "Error while attempting to close data source", e);
            }
        }

        logger.info(RECONNECT.getMarker(), "Finished sending tree");
    }

    private void throwCause(Throwable ex) throws MerkleSynchronizationException {
        if (ex instanceof MerkleSynchronizationException) {
            throw (MerkleSynchronizationException) ex;
        } else if (ex.getCause() instanceof MerkleSynchronizationException) {
            throw (MerkleSynchronizationException) ex.getCause();
        } else {
            throw new MerkleSynchronizationException("Synchronization failed with exceptions", ex);
        }
    }

    protected StandardWorkGroup createStandardWorkGroup(
            @NonNull final ThreadManager threadManager, @NonNull final Runnable breakConnection) {
        return new StandardWorkGroup(threadManager, WORK_GROUP_NAME, breakConnection);
    }

    /**
     * Build the output stream. Exposed to allow unit tests to override implementation to simulate latency.
     */
    protected AsyncOutputStream buildOutputStream(
            @NonNull final DataOutputStream out, @NonNull final VirtualMapSyncConfig syncConfig) {
        return new AsyncOutputStream(
                out,
                syncConfig.asyncStreamBufferSize(),
                syncConfig.asyncOutputStreamFlush(),
                syncConfig.asyncStreamTimeout());
    }

    /**
     * Synchronously reads the root node request from the learner, then sends back the root
     * response containing the teacher's first/last leaf path range. This must complete before
     * any parallel tasks are forked so the learner can initialize its traversal order and begin
     * sending non-root requests.
     *
     * @param teacherView teacher view used to access information about state
     * @param in          the async input stream to read the root request from
     * @param out         the async output stream to send the root response to
     * @throws MerkleSynchronizationException if the exchange fails, times out, or is interrupted
     */
    private void exchangeRootNode(
            final RecordAccessor teacherView, final AsyncInputStream in, final AsyncOutputStream out) {
        final byte[] rootRequestBytes = in.readOrWait(YieldStrategy.PARK);
        if (rootRequestBytes == null) {
            throw new MerkleSynchronizationException("Stream closed before root node request was received");
        }
        final PullVirtualTreeRequest rootRequest =
                PullVirtualTreeRequest.parseFrom(BufferedData.wrap(rootRequestBytes));
        if (rootRequest.path() != Path.ROOT_PATH) {
            throw new MerkleSynchronizationException("Expected root request (path 0), got path " + rootRequest.path());
        }

        final Hash teacherRootHash = teacherView.findHash(Path.ROOT_PATH);
        final boolean isClean = (teacherRootHash == null) || teacherRootHash.equals(rootRequest.hash());
        final long firstLeafPath = teacherView.getMetadata().getFirstLeafPath();
        final long lastLeafPath = teacherView.getMetadata().getLastLeafPath();
        final PullVirtualTreeResponse rootResponse =
                new PullVirtualTreeResponse(Path.ROOT_PATH, isClean, firstLeafPath, lastLeafPath, null);

        logger.info(
                RECONNECT.getMarker(),
                "Teacher sending root node response: firstLeafPath={}, lastLeafPath={}",
                firstLeafPath,
                lastLeafPath);
        final byte[] responseBytes = new byte[rootResponse.getSizeInBytes()];
        rootResponse.writeTo(BufferedData.wrap(responseBytes));
        try {
            out.sendAsync(responseBytes);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MerkleSynchronizationException("Interrupted while sending root node response", e);
        }
    }
}
