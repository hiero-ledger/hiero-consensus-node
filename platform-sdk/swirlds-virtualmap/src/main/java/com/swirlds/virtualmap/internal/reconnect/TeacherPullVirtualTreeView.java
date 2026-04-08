// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.reconnect;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;

import com.swirlds.base.time.Time;
import com.swirlds.common.merkle.synchronization.streams.AsyncInputStream;
import com.swirlds.common.merkle.synchronization.streams.AsyncOutputStream;
import com.swirlds.common.merkle.synchronization.views.TeacherTreeView;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import com.swirlds.virtualmap.internal.RecordAccessor;
import com.swirlds.virtualmap.internal.merkle.VirtualMapMetadata;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.concurrent.pool.StandardWorkGroup;
import org.hiero.consensus.reconnect.config.ReconnectConfig;

/**
 * An implementation of {@link TeacherTreeView} designed for virtual merkle trees.
 *
 * <p>This learner tree view creates two tasks running in the provided work group. One task
 * is responsible for sending requests to the teacher, the other one receives responses. Once
 * both tasks are completed, the corresponding virtual map is fully synchronized with the
 * teacher.
 *
 * <p>This implementation is supposed to work with {@link LearnerPullVirtualTreeView} on the
 * learner side.
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

    @Override
    public void startTeacherTasks(
            final Time time,
            final StandardWorkGroup workGroup,
            final AsyncInputStream in,
            final AsyncOutputStream out) {
        // FUTURE work: pool size config
        final int teacherTasks = 16;
        final AtomicInteger tasksDone = new AtomicInteger(teacherTasks);
        for (int i = 0; i < teacherTasks; i++) {
            final TeacherPullVirtualTreeReceiveTask teacherReceiveTask =
                    new TeacherPullVirtualTreeReceiveTask(time, reconnectConfig, workGroup, in, out, this, tasksDone);
            teacherReceiveTask.exec();
        }
    }

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

    public VirtualMapMetadata getReconnectState() {
        return reconnectState;
    }
}
