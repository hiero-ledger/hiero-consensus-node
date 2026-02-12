// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.reconnect;

import static com.swirlds.logging.legacy.LogMarker.RECONNECT;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import com.swirlds.base.time.Time;
import com.swirlds.common.io.exceptions.MerkleSerializationException;
import com.swirlds.common.merkle.synchronization.streams.AsyncInputStream;
import com.swirlds.common.merkle.synchronization.streams.AsyncOutputStream;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import com.swirlds.virtualmap.internal.Path;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.concurrent.pool.StandardWorkGroup;
import org.hiero.consensus.concurrent.utility.throttle.RateLimiter;
import org.hiero.consensus.reconnect.config.ReconnectConfig;

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
    private final AsyncInputStream in;
    private final AsyncOutputStream out;
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
            final AsyncInputStream in,
            final AsyncOutputStream out,
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
        try {
            long requestCounter = 0;
            final long start = System.currentTimeMillis();
            while (!Thread.currentThread().isInterrupted()) {
                rateLimit();
                final PullVirtualTreeRequest request = in.readAnticipatedMessage(PullVirtualTreeRequest::new);
                if (request == null) {
                    if (!in.isAlive()) {
                        break;
                    }
                    Thread.sleep(0, 1);
                    continue;
                }
                requestCounter++;
                if (request.getPath() == Path.INVALID_PATH) {
                    logger.info(RECONNECT.getMarker(), "Teaching is complete as requested by the learner");
                    // Acknowledge the final request
                    out.sendAsync(new PullVirtualTreeResponse(view, Path.INVALID_PATH, true, -1, -1, null));
                    break;
                }
                final long path = request.getPath();
                final Hash learnerHash = request.getHash();
                assert learnerHash != null;
                final Hash teacherHash = view.loadHash(path);
                // The only valid scenario, when teacherHash may be null, is the empty tree
                if ((teacherHash == null) && (path != 0)) {
                    throw new MerkleSerializationException(
                            "Cannot load node hash (bad request from learner?), path=" + path);
                }
                final boolean isClean = (teacherHash == null) || teacherHash.equals(learnerHash);
                final VirtualLeafBytes<?> leafData = (!isClean && view.isLeaf(path)) ? view.loadLeaf(path) : null;
                final long firstLeafPath = view.getReconnectState().getFirstLeafPath();
                final long lastLeafPath = view.getReconnectState().getLastLeafPath();
                final PullVirtualTreeResponse response =
                        new PullVirtualTreeResponse(view, path, isClean, firstLeafPath, lastLeafPath, leafData);
                out.sendAsync(response);
            }
            final long end = System.currentTimeMillis();
            final double requestRate = (end == start) ? 0.0 : (double) requestCounter / (end - start);
            logger.info(
                    RECONNECT.getMarker(),
                    "Teacher task: duration={}ms, requests={}, rate={}",
                    end - start,
                    requestCounter,
                    requestRate);
        } catch (final InterruptedException ex) {
            logger.warn(RECONNECT.getMarker(), "Teacher task is interrupted");
            Thread.currentThread().interrupt();
        } catch (final Exception ex) {
            workGroup.handleError(ex);
        }
    }
}
