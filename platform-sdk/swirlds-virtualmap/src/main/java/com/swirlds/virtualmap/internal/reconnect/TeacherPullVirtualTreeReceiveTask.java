// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.reconnect;

import static com.swirlds.logging.legacy.LogMarker.RECONNECT;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import com.swirlds.base.time.Time;
import com.swirlds.common.io.exceptions.MerkleSerializationException;
import com.swirlds.common.merkle.synchronization.config.ReconnectConfig;
import com.swirlds.common.merkle.synchronization.streams.AsyncOutputStream;
import com.swirlds.common.merkle.synchronization.utility.MerkleSynchronizationException;
import com.swirlds.common.threading.pool.StandardWorkGroup;
import com.swirlds.common.utility.throttle.RateLimiter;
import com.swirlds.virtualmap.internal.Path;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.crypto.Hash;
import org.hiero.consensus.model.io.streams.SerializableDataInputStream;

/**
 * A task running on the teacher side, which is responsible for processing requests from the
 * learner. For every request, a response is sent to the provided async output stream. Async
 * streams serialize objects to the underlying output streams in a separate thread. This is
 * where the provided hash from the learner is compared with the corresponding hash on the
 * teacher.
 */
public class TeacherPullVirtualTreeReceiveTask {

    private static final Logger logger = LogManager.getLogger(TeacherPullVirtualTreeReceiveTask.class);

    private static final String NAME = "reconnect-teacher-receiver";

    private final StandardWorkGroup workGroup;
    private final SerializableDataInputStream in;
    private final AsyncOutputStream<PullVirtualTreeResponse> out;
    private final TeacherPullVirtualTreeView view;

    private final RateLimiter rateLimiter;
    private final int sleepNanos;

    /**
     * Create new thread that will send data lessons and queries for a subtree.
     *
     * @param time                  the wall clock time
     * @param reconnectConfig       the configuration for reconnect
     * @param workGroup             the work group managing the reconnect
     * @param in                    the input stream
     * @param out                   the output stream
     * @param view                  an object that interfaces with the subtree
     */
    public TeacherPullVirtualTreeReceiveTask(
            @NonNull final Time time,
            @NonNull final ReconnectConfig reconnectConfig,
            final StandardWorkGroup workGroup,
            final SerializableDataInputStream in,
            final AsyncOutputStream<PullVirtualTreeResponse> out,
            final TeacherPullVirtualTreeView view) {
        this.workGroup = workGroup;
        this.in = in;
        this.out = out;
        this.view = view;

        final int maxRate = reconnectConfig.teacherMaxNodesPerSecond();
        if (maxRate > 0) {
            rateLimiter = new RateLimiter(time, maxRate);
            sleepNanos = (int) reconnectConfig.teacherRateLimiterSleep().toNanos();
        } else {
            rateLimiter = null;
            sleepNanos = -1;
        }
    }

    /**
     * Start the thread that sends lessons and queries to the learner.
     */
    void exec() {
        workGroup.execute(NAME, this::run);
    }

    /**
     * Enforce the rate limit.
     *
     * @throws InterruptedException if the thread is interrupted while sleeping
     */
    private void rateLimit() throws InterruptedException {
        if (rateLimiter != null) {
            while (!rateLimiter.requestAndTrigger()) {
                NANOSECONDS.sleep(sleepNanos);
            }
        }
    }

    /**
     * This thread is responsible for sending lessons (and nested queries) to the learner.
     */
    private void run() {
        try (out) {
            while (true) {
                rateLimit();
                final PullVirtualTreeRequest request = new PullVirtualTreeRequest();
                request.deserialize(in, 0);
                logger.debug(RECONNECT.getMarker(), "Teacher receive path: " + request.getPath());
                if (request.getPath() == Path.INVALID_PATH) {
                    logger.info(RECONNECT.getMarker(), "Teacher receiver is complete as requested by the learner");
                    break;
                }
                final long path = request.getPath();
                final Hash learnerHash = request.getHash();
                final Hash teacherHash = view.loadHash(path);
                // The only valid scenario, when teacherHash may be null, is the empty tree
                if ((teacherHash == null) && (path != 0)) {
                    throw new MerkleSerializationException(
                            "Cannot load node hash (bad request from learner?), path = " + path);
                }
                final PullVirtualTreeResponse response =
                        new PullVirtualTreeResponse(view, path, learnerHash, teacherHash);
                // All real work is done in the async output thread. This call just registers a response
                // and returns immediately
                out.sendAsync(response);
            }
            logger.debug(RECONNECT.getMarker(), "Teacher receive done");
        } catch (final InterruptedException ex) {
            logger.warn(RECONNECT.getMarker(), "Teacher's receiving task is interrupted");
            Thread.currentThread().interrupt();
        } catch (final Exception ex) {
            throw new MerkleSynchronizationException("Exception in the teacher's receiving task", ex);
        }
    }
}
