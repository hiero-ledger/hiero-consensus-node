// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.reconnect;

import static com.swirlds.logging.legacy.LogMarker.RECONNECT;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.swirlds.base.time.Time;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import com.swirlds.virtualmap.internal.RecordAccessor;
import com.swirlds.virtualmap.sync.MerkleSynchronizationException;
import com.swirlds.virtualmap.sync.streams.AsyncInputStream;
import com.swirlds.virtualmap.sync.streams.AsyncOutputStream;
import com.swirlds.virtualmap.sync.streams.YieldStrategy;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.CountDownLatch;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.concurrent.pool.StandardWorkGroup;
import org.hiero.consensus.concurrent.throttle.RateLimiter;
import org.hiero.consensus.reconnect.config.ReconnectConfig;

/**
 * A task running on the teacher side, which is responsible for processing requests from the
 * learner. For every request, a response is sent to the provided async output stream. Async
 * streams serialize objects to the underlying output streams in a separate thread. This is
 * where the provided hash from the learner is compared with the corresponding hash on the
 * teacher.
 *
 * <p>This task terminates either on exception or when no messages are returned by {@link AsyncInputStream}.
 */
public class TeacherPullVirtualTreeReceiveTask {

    private static final Logger logger = LogManager.getLogger(TeacherPullVirtualTreeReceiveTask.class);

    private static final String NAME = "reconnect-teacher-receiver";

    private final StandardWorkGroup workGroup;
    private final AsyncInputStream in;
    private final AsyncOutputStream out;
    private final RecordAccessor teacherView;
    private final CountDownLatch tasksDone;

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
     * @param teacherView           view of teacher state
     */
    public TeacherPullVirtualTreeReceiveTask(
            @NonNull final Time time,
            @NonNull final ReconnectConfig reconnectConfig,
            final StandardWorkGroup workGroup,
            final AsyncInputStream in,
            final AsyncOutputStream out,
            final RecordAccessor teacherView,
            final CountDownLatch tasksDone) {
        this.workGroup = workGroup;
        this.in = in;
        this.out = out;
        this.teacherView = teacherView;
        this.tasksDone = tasksDone;

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
    public void exec() {
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
                final byte[] requestBytes = in.readOrWait(YieldStrategy.SLEEP);
                if (requestBytes == null) {
                    break;
                }
                final PullVirtualTreeRequest request =
                        PullVirtualTreeRequest.parseFrom(BufferedData.wrap(requestBytes));
                requestCounter++;

                if (request.path() < 0) {
                    throw new IllegalStateException("Invalid path received from learner: " + request.path());
                }

                final long path = request.path();
                final Hash learnerHash = request.hash();
                assert learnerHash != null;
                final Hash teacherHash = teacherView.findHash(path);
                // The only valid scenario, when teacherHash may be null, is the empty tree
                if ((teacherHash == null) && (path != 0)) {
                    throw new MerkleSynchronizationException(
                            "Cannot load node hash (bad request from learner?), path=" + path);
                }
                final boolean isClean = (teacherHash == null) || teacherHash.equals(learnerHash);
                final VirtualLeafBytes<?> leafData =
                        (!isClean && teacherView.isLeaf(path)) ? teacherView.findLeafRecord(path) : null;
                final long firstLeafPath = teacherView.getMetadata().getFirstLeafPath();
                final long lastLeafPath = teacherView.getMetadata().getLastLeafPath();
                final PullVirtualTreeResponse response =
                        new PullVirtualTreeResponse(path, isClean, firstLeafPath, lastLeafPath, leafData);
                out.sendAsync(serializeMessage(response));
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
        } finally {
            tasksDone.countDown();
        }
    }

    /**
     * Serializes the given response into a byte array suitable for sending via the async
     * output stream.
     *
     * @param response the response to serialize
     * @return the serialized bytes
     */
    private static byte[] serializeMessage(final PullVirtualTreeResponse response) {
        final byte[] bytes = new byte[response.getSizeInBytes()];
        response.writeTo(BufferedData.wrap(bytes));
        return bytes;
    }
}
