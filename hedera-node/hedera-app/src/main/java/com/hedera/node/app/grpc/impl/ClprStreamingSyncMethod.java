// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.grpc.impl;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.clpr.ClprStreamingSyncPayload;
import com.hedera.node.app.workflows.clpr.ClprStreamingSyncSession;
import com.hedera.node.app.workflows.clpr.ClprSyncWorkflow;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ServerCalls;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * gRPC method for the bidirectional-streaming CLPR sync {@code streamingSync}: marshaling, and closing the call at
 * the right moment. All protocol decisions belong to the {@link ClprStreamingSyncSession} this creates per stream.
 *
 * <p>A single instance serves every stream; the per-stream state lives in the session.
 */
/*@ThreadSafe*/
public final class ClprStreamingSyncMethod implements ServerCalls.BidiStreamingMethod<BufferedData, BufferedData> {
    private static final Logger logger = LogManager.getLogger(ClprStreamingSyncMethod.class);

    /** Source of the process-local call id that separates successive streams, shared with the session it opens. */
    private static final AtomicLong CALL_COUNTER = new AtomicLong();

    private final ClprSyncWorkflow workflow;

    public ClprStreamingSyncMethod(@NonNull final ClprSyncWorkflow workflow) {
        this.workflow = requireNonNull(workflow);
    }

    @Override
    public StreamObserver<BufferedData> invoke(@NonNull final StreamObserver<BufferedData> responseObserver) {
        final String correlationId = "clpr-sync[" + CALL_COUNTER.incrementAndGet() + "]";
        final ClprStreamingSyncSession session;
        try {
            session = workflow.openStreamingSync(correlationId);
        } catch (final Exception e) {
            responseObserver.onError(e);
            // gRPC may still deliver callbacks after the call is closed; returning null would cause an NPE.
            return noOpStreamObserver();
        }
        return new SyncStreamObserver(correlationId, session, responseObserver);
    }

    @NonNull
    private static BufferedData toBuffer(@NonNull final ClprStreamingSyncPayload reply) throws IOException {
        final var buffer = BufferedData.allocate(ClprStreamingSyncPayload.PROTOBUF.measureRecord(reply));
        ClprStreamingSyncPayload.PROTOBUF.write(reply, buffer);
        buffer.flip();
        return buffer;
    }

    @NonNull
    private static StreamObserver<BufferedData> noOpStreamObserver() {
        return new StreamObserver<>() {
            @Override
            public void onNext(final BufferedData ignored) {
                // The call is already closed; anything still in flight is discarded.
            }

            @Override
            public void onError(final Throwable ignored) {
                // no-op
            }

            @Override
            public void onCompleted() {
                // no-op
            }
        };
    }

    private static class SyncStreamObserver implements StreamObserver<BufferedData> {
        private static final Logger LOG = LogManager.getLogger(SyncStreamObserver.class.getName());

        private final ClprStreamingSyncSession session;
        private final StreamObserver<BufferedData> responseObserver;

        /** Log prefix tying every line of this exchange together: the call id, shared with the session it drives. */
        private final String tag;

        /** Guards against a second terminal callback on this call, which gRPC rejects. */
        private boolean closed;

        /** Messages received from the peer so far.*/
        private int readsCount = 0;

        /** Messages written back to the peer so far. */
        private int writesCount = 0;

        public SyncStreamObserver(
                final String correlationId,
                final ClprStreamingSyncSession session,
                final StreamObserver<BufferedData> responseObserver) {
            this.session = session;
            this.responseObserver = responseObserver;
            this.tag = correlationId;
            closed = false;
        }

        @Override
        public void onNext(final BufferedData requestBuffer) {
            LOG.debug("{} new streaming message received", tag);
            if (closed) {
                return;
            }
            final int readSeq = ++readsCount;
            try {
                // The marshaller uses a thread-local buffer, so the bytes must be consumed before this
                // call returns — parsing here, inside the session, satisfies that.
                final var response = session.onMessage(requestBuffer.getBytes(0, requestBuffer.length()));
                if (response != null) {
                    final int writeSeq = ++writesCount;
                    LOG.debug(
                            "{} readNumber={} writeNumber={} sending response to CLPR streaming sync message",
                            tag,
                            readSeq,
                            writeSeq);
                    responseObserver.onNext(toBuffer(response));
                } else {
                    LOG.debug("{} readNumber={} no response to send", tag, readSeq);
                }
                // Close only once BOTH sides have gone terminal. Closing as soon as this side runs out would
                // drop whatever the peer was still about to write.
                if (session.isComplete()) {
                    complete();
                }
            } catch (final Exception e) {
                if (closed) {
                    return;
                }
                closed = true;
                if (!(e instanceof StatusRuntimeException)) {
                    logger.error("{} unexpected exception while handling a CLPR streaming sync message", tag, e);
                }
                responseObserver.onError(e);
            }
        }

        @Override
        public void onError(final Throwable t) {
            // The peer aborted or canceled. There is nothing to release: the session holds no resources
            // beyond the partial exchange state, which is discarded with it.
            closed = true;
            logger.warn("{} stream aborted by peer after {} read(s) and {} write(s)", tag, readsCount, writesCount, t);
        }

        @Override
        public void onCompleted() {
            // The peer half-closed, so it will send nothing more; this side is free to close too.
            LOG.debug(
                    "{} CLPR streaming sync message stream completed. {} read(s) {} write(s)",
                    tag,
                    readsCount,
                    writesCount);
            complete();
        }

        private void complete() {
            if (closed) {
                return;
            }
            closed = true;
            LOG.debug("{} closing after {} read(s) and {} write(s)", tag, readsCount, writesCount);
            responseObserver.onCompleted();
        }
    }
}
