// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.reconnect;

import static com.swirlds.logging.legacy.LogMarker.RECONNECT;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.swirlds.virtualmap.RecordAccessor;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import com.swirlds.virtualmap.sync.MerkleSynchronizationException;
import com.swirlds.virtualmap.sync.streams.AsyncInputStream;
import com.swirlds.virtualmap.sync.streams.AsyncOutputStream;
import com.swirlds.virtualmap.sync.streams.YieldStrategy;
import java.util.concurrent.CountDownLatch;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.Hash;

/**
 * A task running on the teacher side, which is responsible for processing requests from the
 * learner. For every request, a response is sent to the provided async output stream. Async
 * streams serialize objects to the underlying output streams in a separate thread. This is
 * where the provided hash from the learner is compared with the corresponding hash on the
 * teacher.
 *
 * <p>This task terminates either on exception or when no messages are returned by {@link AsyncInputStream}.
 */
public class TeacherPullVirtualTreeReceiveTask implements Runnable {

    private static final Logger logger = LogManager.getLogger(TeacherPullVirtualTreeReceiveTask.class);

    private final AsyncInputStream in;
    private final AsyncOutputStream out;
    private final RecordAccessor teacherView;
    private final CountDownLatch tasksDone;

    /**
     * Create new thread that will send data lessons and queries for a subtree.
     *
     * @param in                    the input stream
     * @param out                   the output stream
     * @param teacherView           view of teacher state
     */
    public TeacherPullVirtualTreeReceiveTask(
            final AsyncInputStream in,
            final AsyncOutputStream out,
            final RecordAccessor teacherView,
            final CountDownLatch tasksDone) {
        this.in = in;
        this.out = out;
        this.teacherView = teacherView;
        this.tasksDone = tasksDone;
    }

    /**
     * This thread is responsible for sending lessons (and nested queries) to the learner.
     */
    @Override
    public void run() {
        long readBlockedNanos = 0; // waiting for a request to arrive (teacher starved)
        long produceNanos = 0; // findHash + isLeaf/findLeafRecord + metadata (teacher's MerkleDB data path)
        long buildNanos = 0; // construct the response object (CPU)
        long serializeNanos = 0; // PBJ serialize to byte[] (CPU)
        long enqueueNanos = 0; // sendAsync — the WAIT on a full async-out queue (blocked/spinning)
        try {
            long requestCounter = 0;
            final long start = System.currentTimeMillis();
            while (!Thread.currentThread().isInterrupted()) {
                final long t0 = System.nanoTime();
                final byte[] requestBytes = in.readOrWait(YieldStrategy.SLEEP);
                if (requestBytes == null) {
                    break;
                }
                readBlockedNanos += System.nanoTime() - t0;

                final PullVirtualTreeRequest request =
                        PullVirtualTreeRequest.parseFrom(BufferedData.wrap(requestBytes));
                requestCounter++;

                if (request.path() < 0) {
                    throw new IllegalStateException("Invalid path received from learner: " + request.path());
                }
                final long t1 = System.nanoTime();

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
                final long t2 = System.nanoTime();

                final PullVirtualTreeResponse response =
                        new PullVirtualTreeResponse(path, isClean, firstLeafPath, lastLeafPath, leafData);
                final long t3 = System.nanoTime();
                final byte[] serialized = serializeMessage(response);
                final long t4 = System.nanoTime();
                out.sendAsync(serialized);
                final long t5 = System.nanoTime();

                produceNanos += (t2 - t1);
                buildNanos += (t3 - t2);
                serializeNanos += (t4 - t3);
                enqueueNanos += (t5 - t4);
            }
            final long end = System.currentTimeMillis();
            final double requestRate = (end == start) ? 0.0 : (double) requestCounter / (end - start);
            logger.info(
                    RECONNECT.getMarker(),
                    "Teacher task: duration={}ms, requests={}, rate={}, readBlockedMs={}, produceMs={}, "
                            + "buildMs={}, serializeMs={}, enqueueMs={}",
                    end - start,
                    requestCounter,
                    requestRate,
                    NANOSECONDS.toMillis(readBlockedNanos),
                    NANOSECONDS.toMillis(produceNanos),
                    NANOSECONDS.toMillis(buildNanos),
                    NANOSECONDS.toMillis(serializeNanos),
                    NANOSECONDS.toMillis(enqueueNanos));
        } catch (final InterruptedException ex) {
            logger.warn(RECONNECT.getMarker(), "Teacher task is interrupted");
            Thread.currentThread().interrupt();
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
