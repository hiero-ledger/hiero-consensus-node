// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.synchronization;

import static com.swirlds.logging.legacy.LogMarker.RECONNECT;

import com.swirlds.base.time.Time;
import com.swirlds.common.merkle.synchronization.streams.AsyncInputStream;
import com.swirlds.common.merkle.synchronization.streams.AsyncOutputStream;
import com.swirlds.common.merkle.synchronization.utility.MerkleSynchronizationException;
import com.swirlds.common.merkle.synchronization.views.TeacherTreeView;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.net.SocketException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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

    private final StandardWorkGroup workGroup;

    private final AtomicReference<Throwable> firstReconnectException = new AtomicReference<>();

    /**
     * Used to get data from the listener.
     */
    private final SerializableDataInputStream inputStream;

    /**
     * Used to transmit data to the listener.
     */
    private final SerializableDataOutputStream outputStream;

    private final TeacherTreeView view;

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
        inputStream = Objects.requireNonNull(in, "in must not be null");
        outputStream = Objects.requireNonNull(out, "out must not be null");

        this.view = Objects.requireNonNull(view, "view must not be null");

        this.reconnectConfig = Objects.requireNonNull(reconnectConfig, "reconnectConfig must not be null");

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
     * Perform synchronization in the role of the teacher.
     */
    public void synchronize() throws InterruptedException {
        final AsyncInputStream in = new AsyncInputStream(inputStream, workGroup, reconnectConfig);
        in.start();
        final AsyncOutputStream out = buildOutputStream(workGroup, outputStream, in::isAlive, reconnectConfig);
        out.start();

        InterruptedException interruptException = null;
        try (view) {
            view.startTeacherTasks(this, time, workGroup, in, out);
            workGroup.waitForTermination();
        } catch (final InterruptedException e) { // NOSONAR: Exception is rethrown below after cleanup.
            interruptException = e;
            logger.warn(RECONNECT.getMarker(), "Interrupted while waiting for work group termination");
        } catch (final Throwable t) {
            logger.info(RECONNECT.getMarker(), "Caught exception while sending tree", t);
            throw t;
        }

        if ((interruptException != null) || workGroup.hasExceptions()) {
            in.abort();
            if (interruptException != null) {
                throw interruptException;
            }
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
            @NonNull final SerializableDataOutputStream out,
            @NonNull final Supplier<Boolean> alive,
            @NonNull final ReconnectConfig reconnectConfig) {
        return new AsyncOutputStream(out, workGroup, alive, reconnectConfig);
    }
}
