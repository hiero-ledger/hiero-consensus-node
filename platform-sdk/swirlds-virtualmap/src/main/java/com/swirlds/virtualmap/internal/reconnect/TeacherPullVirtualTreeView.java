// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.reconnect;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.RECONNECT;

import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.swirlds.base.time.Time;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import com.swirlds.virtualmap.internal.Path;
import com.swirlds.virtualmap.internal.RecordAccessor;
import com.swirlds.virtualmap.internal.merkle.VirtualMapMetadata;
import com.swirlds.virtualmap.sync.MerkleSynchronizationException;
import com.swirlds.virtualmap.sync.TeacherTreeView;
import com.swirlds.virtualmap.sync.streams.AsyncInputStream;
import com.swirlds.virtualmap.sync.streams.AsyncOutputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.concurrent.pool.StandardWorkGroup;
import org.hiero.consensus.reconnect.config.ReconnectConfig;

/**
 * A teacher tree view for virtual map reconnect.
 */
public final class TeacherPullVirtualTreeView implements TeacherTreeView {

    private static final Logger logger = LogManager.getLogger(TeacherPullVirtualTreeView.class);
    /**
     * The state representing the tree being reconnected. For the teacher, this corresponds to the saved state.
     * For the learner, this is the state of the tree being serialized into.
     */
    private final VirtualMapMetadata reconnectState;

    private final ReconnectConfig reconnectConfig;

    /**
     * The {@link RecordAccessor} used for accessing the original map state.
     */
    private final RecordAccessor records;

    /**
     * Create a new {@link TeacherPullVirtualTreeView}.
     *
     * @param map
     * 		The map node on the teacher side of the saved state that we are going to reconnect.
     */
    public TeacherPullVirtualTreeView(final ReconnectConfig reconnectConfig, final VirtualMap map) {
        this.reconnectConfig = reconnectConfig;
        this.records = map.detach();
        this.reconnectState = map.getMetadata();
    }

    /** {@inheritDoc} */
    @Override
    public void startTeacherTasks(
            final Time time,
            final StandardWorkGroup workGroup,
            final AsyncInputStream in,
            final AsyncOutputStream out) {
        // Perform the root-node (path 0) request/response handshake synchronously before forking
        // any parallel tasks. The root response carries the teacher's first/last leaf path range.
        // Once sent, the learner will start sending non-root requests, which the parallel tasks
        // will process. This guarantees no task races for the first message on the stream.
        exchangeRootNode(in, out);

        // FUTURE work: pool size config
        final int teacherTasks = 16;
        final AtomicInteger tasksDone = new AtomicInteger(teacherTasks);
        for (int i = 0; i < teacherTasks; i++) {
            final TeacherPullVirtualTreeReceiveTask teacherReceiveTask =
                    new TeacherPullVirtualTreeReceiveTask(time, reconnectConfig, workGroup, in, out, this, tasksDone);
            teacherReceiveTask.exec();
        }
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
        final byte[] rootRequestBytes = in.readAnticipatedMessageSync();
        if (rootRequestBytes == null) {
            throw new MerkleSynchronizationException("Stream closed before root node request was received");
        }
        final PullVirtualTreeRequest rootRequest =
                PullVirtualTreeRequest.parseFrom(BufferedData.wrap(rootRequestBytes));
        if (rootRequest.path() != Path.ROOT_PATH) {
            throw new MerkleSynchronizationException("Expected root request (path 0), got path " + rootRequest.path());
        }

        final Hash teacherRootHash = loadHash(Path.ROOT_PATH);
        final boolean isClean = (teacherRootHash == null) || teacherRootHash.equals(rootRequest.hash());
        final long firstLeafPath = reconnectState.getFirstLeafPath();
        final long lastLeafPath = reconnectState.getLastLeafPath();
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

    /**
     * Determines if a given path refers to a leaf of the teacher's tree.
     *
     * @param path the virtual path
     * @return {@code true} if the path is within the leaf range
     */
    public boolean isLeaf(final long path) {
        return (path >= reconnectState.getFirstLeafPath())
                && (path <= reconnectState.getLastLeafPath())
                && (reconnectState.getFirstLeafPath() > 0);
    }

    /**
     * Read the virtual hash identified by a given path.
     *
     * @param path the virtual path
     * @return the node hash
     */
    public Hash loadHash(final long path) {
        return path == 0 ? records.rootHash() : records.findHash(path);
    }

    /**
     * Read the virtual leaf identified by a given path.
     *
     * @param path the virtual path
     * @return the leaf
     */
    public VirtualLeafBytes<?> loadLeaf(final long path) {
        return records.findLeafRecord(path);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() {
        try {
            records.close();
        } catch (final IOException e) {
            logger.error(EXCEPTION.getMarker(), "Interrupted while attempting to close data source");
        }
    }

    /**
     * Returns the metadata for the tree being reconnected on the teacher side.
     *
     * @return the reconnect state metadata
     */
    public VirtualMapMetadata getReconnectState() {
        return reconnectState;
    }
}
