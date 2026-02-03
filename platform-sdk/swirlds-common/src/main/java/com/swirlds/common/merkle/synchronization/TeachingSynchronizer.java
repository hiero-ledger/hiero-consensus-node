// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.synchronization;

import static com.swirlds.logging.legacy.LogMarker.RECONNECT;

import com.swirlds.base.time.Time;
import com.swirlds.common.merkle.synchronization.streams.AsyncOutputStream;
import com.swirlds.common.merkle.synchronization.utility.MerkleSynchronizationException;
import com.swirlds.common.merkle.synchronization.views.TeacherTreeView;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.net.SocketException;
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
 * Performs synchronization in the role of the teacher.
 */
public class TeachingSynchronizer {

    private static final String WORK_GROUP_NAME = "teaching-synchronizer";

    private static final Logger logger = LogManager.getLogger(TeachingSynchronizer.class);

    /**
     * Used to get data from the listener.
     */
    private final SerializableDataInputStream inputStream;

    /**
     * Used to transmit data to the listener.
     */
    private final SerializableDataOutputStream outputStream;

    private final TeacherTreeView view;

    private final Runnable breakConnection;

    /**
     * Responsible for creating and managing threads used by this object.
     */
    private final ThreadManager threadManager;

    protected final ReconnectConfig reconnectConfig;

    private final Time time;

    /**
     * Create a new teaching synchronizer.
     *
     * @param threadManager   responsible for managing thread lifecycles
     * @param in              the input stream
     * @param out             the output stream
     * @param view            the teacher tree view, used to access all tree nodes
     * @param breakConnection a method that breaks the connection. Used iff an exception is encountered. Prevents
     *                        deadlock if there is a thread stuck on a blocking IO operation that will never finish due
     *                        to a failure.
     * @param reconnectConfig reconnect configuration from platform
     */
    public TeachingSynchronizer(
            @NonNull final Time time,
            @NonNull final ThreadManager threadManager,
            @NonNull final SerializableDataInputStream in,
            @NonNull final SerializableDataOutputStream out,
            @NonNull final TeacherTreeView view,
            @Nullable final Runnable breakConnection,
            @NonNull final ReconnectConfig reconnectConfig) {

        this.time = Objects.requireNonNull(time);
        this.threadManager = Objects.requireNonNull(threadManager, "threadManager must not be null");
        inputStream = Objects.requireNonNull(in, "in must not be null");
        outputStream = Objects.requireNonNull(out, "out must not be null");

        this.view = Objects.requireNonNull(view, "view must not be null");

        this.breakConnection = breakConnection;
        this.reconnectConfig = Objects.requireNonNull(reconnectConfig, "reconnectConfig must not be null");
    }

    /**
     * Perform synchronization in the role of the teacher.
     */
    public void synchronize() throws InterruptedException {
        try {
            sendTree();
        } finally {
            view.close();
        }
    }

    /**
     * Send the tree.
     */
    private void sendTree() throws InterruptedException {
        final AtomicReference<Throwable> firstReconnectException = new AtomicReference<>();
        // A future improvement might be to reuse threads between subtrees.
        final StandardWorkGroup workGroup = createStandardWorkGroup(threadManager, breakConnection, cause -> {
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
            firstReconnectException.compareAndSet(null, cause);
            // Let StandardWorkGroup log it as an error using the EXCEPTION marker
            return false;
        });

        view.startTeacherTasks(this, time, workGroup, inputStream, outputStream);

        workGroup.waitForTermination();

        if (workGroup.hasExceptions()) {
            throw new MerkleSynchronizationException(
                    "Synchronization failed with exceptions", firstReconnectException.get());
        }

        logger.info(RECONNECT.getMarker(), "finished sending tree");
    }

    protected StandardWorkGroup createStandardWorkGroup(
            ThreadManager threadManager, Runnable breakConnection, Function<Throwable, Boolean> exceptionListener) {
        return new StandardWorkGroup(threadManager, WORK_GROUP_NAME, breakConnection, exceptionListener);
    }

    /**
     * Build the output stream. Exposed to allow unit tests to override implementation to simulate latency.
     */
    public <T extends SelfSerializable> AsyncOutputStream<T> buildOutputStream(
            final StandardWorkGroup workGroup, final SerializableDataOutputStream out) {
        return new AsyncOutputStream<>(out, workGroup, reconnectConfig);
    }
}
