// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.sync;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.RECONNECT;

import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.swirlds.base.time.Time;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.internal.Path;
import com.swirlds.virtualmap.internal.RecordAccessor;
import com.swirlds.virtualmap.internal.reconnect.PullVirtualTreeRequest;
import com.swirlds.virtualmap.internal.reconnect.PullVirtualTreeResponse;
import com.swirlds.virtualmap.internal.reconnect.TeacherPullVirtualTreeReceiveTask;
import com.swirlds.virtualmap.sync.streams.AsyncInputStream;
import com.swirlds.virtualmap.sync.streams.AsyncOutputStream;
import com.swirlds.virtualmap.sync.streams.YieldStrategy;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.SocketException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.concurrent.manager.ThreadManager;
import org.hiero.consensus.concurrent.pool.StandardWorkGroup;
import org.hiero.consensus.reconnect.config.ReconnectConfig;

/**
 * Performs reconnect in the role of the teacher.
 */
public class TeachingSynchronizer {

    private static final Logger logger = LogManager.getLogger(TeachingSynchronizer.class);

    private static final String WORK_GROUP_NAME = "reconnect-teacher";

    private final Time time;
    private final DataInputStream inputStream;
    private final DataOutputStream outputStream;
    private final RecordAccessor teacherView;
    private final ReconnectConfig reconnectConfig;
    private final StandardWorkGroup workGroup;
    private final AtomicReference<Throwable> firstReconnectException = new AtomicReference<>();

    /**
     * Constructs a new teaching synchronizer.
     *
     * @param time the wall clock time
     * @param threadManager responsible for managing thread lifecycles
     * @param in the input stream for receiving data from the learner
     * @param out the output stream for sending data to the learner
     * @param teacherMap the teacher's view into the merkle tree being synchronized
     * @param breakConnection a callback to disconnect the connection on failure
     * @param reconnectConfig the reconnect configuration
     */
    public TeachingSynchronizer(
            @NonNull final Time time,
            @NonNull final ThreadManager threadManager,
            @NonNull final DataInputStream in,
            @NonNull final DataOutputStream out,
            @NonNull final VirtualMap teacherMap,
            @NonNull final Runnable breakConnection,
            @NonNull final ReconnectConfig reconnectConfig) {

        this.time = Objects.requireNonNull(time, "time is null");
        this.inputStream = Objects.requireNonNull(in, "inputStream is null");
        this.outputStream = Objects.requireNonNull(out, "outputStream is null");
        this.teacherView =
                Objects.requireNonNull(teacherMap, "teacherMap is null").detach();
        this.reconnectConfig = Objects.requireNonNull(reconnectConfig, "reconnectConfig is null");

        final Function<Throwable, Boolean> reconnectExceptionListener = e -> {
            Throwable cause = e;
            while (cause != null) {
                if (cause instanceof SocketException socketEx) {
                    if (socketEx.getMessage().equalsIgnoreCase("Connection reset by peer")) {
                        // Connection issues during reconnects are expected and recoverable, just
                        // log them as info. All other exceptions should be treated as real errors
                        logger.info(RECONNECT.getMarker(), "Connection reset while sending tree. Aborting");
                        return true;
                    }
                }
                cause = cause.getCause();
            }
            firstReconnectException.compareAndSet(null, e);
            // Let StandardWorkGroup log it as an error using the EXCEPTION marker
            return false;
        };
        workGroup = createStandardWorkGroup(threadManager, breakConnection, reconnectExceptionListener);
    }

    /**
     * Perform reconnect in the role of the teacher.
     */
    public void synchronize() throws InterruptedException {
        final AsyncInputStream in = new AsyncInputStream(
                inputStream, workGroup, reconnectConfig.asyncStreamBufferSize(), reconnectConfig.asyncStreamTimeout());
        in.start();
        final AsyncOutputStream out = buildOutputStream(workGroup, outputStream, reconnectConfig);
        out.start();

        try {
            // Perform the root-node (path 0) request/response handshake synchronously before forking
            // any parallel tasks. The root response carries the teacher's first/last leaf path range.
            // Once sent, the learner will start sending non-root requests, which the parallel tasks
            // will process. This guarantees no task races for the first message on the stream.

            exchangeRootNode(in, out);

            // FUTURE work: pool size config
            final int teacherTasks = 16;
            final AtomicInteger tasksDone = new AtomicInteger(teacherTasks);
            for (int i = 0; i < teacherTasks; i++) {
                final TeacherPullVirtualTreeReceiveTask teacherReceiveTask = new TeacherPullVirtualTreeReceiveTask(
                        time, reconnectConfig, workGroup, in, out, teacherView, tasksDone);
                teacherReceiveTask.exec();
            }

            workGroup.waitForTermination();
        } catch (final InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw ie;
        } catch (final Throwable t) {
            logger.info(RECONNECT.getMarker(), "Caught exception while synchronizing tree", t);
            workGroup.handleError(t);
        } finally {
            try {
                teacherView.close();
            } catch (IOException e) {
                logger.error(EXCEPTION.getMarker(), "Error while attempting to close data source");
            }
        }

        if (workGroup.hasExceptions()) {
            throw new MerkleSynchronizationException(
                    "Synchronization failed with exceptions", firstReconnectException.get());
        }

        logger.info(RECONNECT.getMarker(), "Finished sending tree");
    }

    protected StandardWorkGroup createStandardWorkGroup(
            @NonNull final ThreadManager threadManager,
            @NonNull final Runnable breakConnection,
            @Nullable final Function<Throwable, Boolean> exceptionListener) {
        return new StandardWorkGroup(threadManager, WORK_GROUP_NAME, breakConnection, exceptionListener);
    }

    /**
     * Build the output stream. Exposed to allow unit tests to override implementation to simulate latency.
     */
    protected AsyncOutputStream buildOutputStream(
            @NonNull final StandardWorkGroup workGroup,
            @NonNull final DataOutputStream out,
            @NonNull final ReconnectConfig reconnectConfig) {
        return new AsyncOutputStream(
                out,
                workGroup,
                reconnectConfig.asyncStreamBufferSize(),
                reconnectConfig.asyncOutputStreamFlush(),
                reconnectConfig.asyncStreamTimeout());
    }

    /**
     * Synchronously reads the root node request from the learner, then sends back the root
     * response containing the teacher's first/last leaf path range. This must complete before
     * any parallel tasks are forked so the learner can initialize its traversal order and begin
     * sending non-root requests.
     *
     * @param in  the async input stream to read the root request from
     * @param out the async output stream to send the root response to
     * @throws MerkleSynchronizationException if the exchange fails, times out, or is interrupted
     */
    private void exchangeRootNode(final AsyncInputStream in, final AsyncOutputStream out) {
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
