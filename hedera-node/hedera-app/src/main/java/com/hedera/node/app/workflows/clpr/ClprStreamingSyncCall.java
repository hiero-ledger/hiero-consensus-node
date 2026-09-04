// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.clpr.ClprStreamingSyncPayload;
import com.hedera.node.app.workflows.clpr.ClprEndpointClient.ClprSyncException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.grpc.stub.BlockingClientCall;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A live {@code streamingSync} exchange, initiated from the client-side.
 * The caller writes and reads {@link ClprStreamingSyncPayload} messages directly
 * in whatever order the protocol calls for, then half-closes once it has no more messages to send.
 * An instance of this class is created by {@link ClprEndpointClient#streamingSync(java.time.Duration)} and its
 * lifecycle is tied to the lifecycle of the stream. Once the stream is closed, this instance is no longer usable.
 *
 * <p>This class is {@link AutoCloseable} and callers are expected to use try-with-resources. An exchange that exits
 * early — a failed write, a malformed peer message, an interrupt — otherwise leaves the underlying gRPC call pinned
 * until its deadline expires. {@link #close()} cancels a call that was never cleanly finished and is a no-op once
 * {@link #cancel} has run, so it is safe to pair with an explicit {@link #halfClose()} on the happy path.
 *
 * <p>Not thread-safe: a single thread must drive the whole exchange, including {@link #close()}. The lifecycle flags
 * below are unsynchronized on that basis.
 */
public final class ClprStreamingSyncCall implements AutoCloseable {
    private static final Logger LOG = LogManager.getLogger(ClprStreamingSyncCall.class);

    /** Source of the process-local call id that separates successive exchanges over the same connection. */
    private static final AtomicLong CALL_COUNTER = new AtomicLong();

    private final BlockingClientCall<byte[], byte[]> call;

    /** Log prefix tying every line of this exchange together: the call id and the peer authority. */
    private final String tag;

    /** Whether writes have already been ended, by either {@link #halfClose()} or {@link #cancel}. */
    private boolean writeClosed = false;

    /** Whether the call has been canceled, so {@link #close()} knows there is nothing left to release. */
    private boolean cancelled = false;

    /** Whether the peer has closed its side cleanly, i.e., a {@link #read()} has returned {@code null}. */
    private boolean peerClosed = false;

    /** Messages successfully handed to the stream so far; logged as {@code w#n} for ordering and gap detection. */
    private int writesCount = 0;

    /** Messages received from the peer so far; logged as {@code r#n}. */
    private int readsCount = 0;

    ClprStreamingSyncCall(@NonNull final BlockingClientCall<byte[], byte[]> call, @NonNull final String peer) {
        this.call = requireNonNull(call);
        requireNonNull(peer);
        this.tag = "clpr-sync[" + CALL_COUNTER.incrementAndGet() + "] peer=" + peer;
    }

    /**
     * Writes one message to the peer.
     *
     * @throws ClprSyncException if the write fails, if the thread is interrupted while waiting for the stream to
     *     become ready, or if the peer has already closed the stream so that the message would be silently dropped
     */
    public void write(@NonNull final ClprStreamingSyncPayload payload) throws ClprSyncException {
        requireNonNull(payload);
        if (writeClosed) {
            throw new ClprSyncException("Streaming sync write attempted after halfClose or cancel");
        }
        final int seq = writesCount + 1;
        final boolean sent;
        try {
            final var bytes = ClprStreamingSyncPayload.PROTOBUF.toBytes(payload).toByteArray();
            if (LOG.isDebugEnabled()) {
                LOG.debug(
                        "{} writeNumber={} writing payload. connection={}, bundleRequest={}, bundleResponse present? {}",
                        tag,
                        seq,
                        payload.channelId(),
                        describeRequest(payload),
                        payload.bundleResponse() != null);
            }
            sent = call.write(bytes);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            cancel("Interrupted during streaming sync write", e);
            throw new ClprSyncException("Streaming sync write interrupted", e);
        } catch (final Exception e) {
            throw new ClprSyncException("Streaming sync write failed: " + e.getMessage(), e);
        }
        // grpc returns false — rather than throwing — when the peer has already closed the stream with OK, in which
        // case the message is dropped without ever reaching the wire.
        if (!sent) {
            throw new ClprSyncException(
                    "Streaming sync write skipped: the peer closed the stream before this message was sent");
        }
        writesCount = seq;
    }

    /**
     * Blocks for the peer's next message.
     *
     * @return the peer's next message, or {@code null} if the peer closed the stream cleanly
     * @throws ClprSyncException if the read fails, if the peer's message cannot be parsed, or if the thread is
     *     interrupted while waiting
     */
    @Nullable
    public ClprStreamingSyncPayload read() throws ClprSyncException {
        try {
            final var bytes = call.read();
            if (bytes == null) {
                LOG.debug("{} peer closed the stream after {} message(s)", tag, readsCount);
                peerClosed = true;
                return null;
            }
            final var payload = ClprStreamingSyncPayload.PROTOBUF.parse(Bytes.wrap(bytes));
            final int seq = ++readsCount;
            if (LOG.isDebugEnabled()) {
                LOG.debug(
                        "{} readNumber={} read payload. connection={}, bundleRequest={}, bundleResponse present? {}",
                        tag,
                        seq,
                        payload.channelId(),
                        describeRequest(payload),
                        payload.bundleResponse() != null);
            }
            return payload;
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            cancel("Interrupted during streaming sync read", e);
            throw new ClprSyncException("Streaming sync read interrupted", e);
        } catch (final Exception e) {
            throw new ClprSyncException("Streaming sync read failed: " + e.getMessage(), e);
        }
    }

    /**
     * Signals that this side has no more messages to send. The peer may still send further messages until it, in turn,
     * closes the stream. Idempotent: calling this after a previous {@code halfClose} or a {@link #cancel} does
     * nothing, so it is safe in a {@code finally} block without masking the exception that got us there.
     */
    public void halfClose() {
        if (writeClosed) {
            return;
        }
        LOG.debug("{} no more messages to send, half closing after {} write(s)", tag, writesCount);
        writeClosed = true;
        call.halfClose();
    }

    /**
     * Releases the stream. On the happy path — this side half-closed and the peer's close was observed by a
     * {@link #read()} returning {@code null} — there is nothing left to release and this does nothing. Otherwise, the
     * call is canceled, so a caller that abandons the exchange part-way does not leave it pinned until the deadline
     * expires.
     */
    @Override
    public void close() {
        if (writeClosed && peerClosed) {
            return;
        }
        LOG.debug(
                "{} closed after {} write(s) and {} read(s) without completing the exchange",
                tag,
                writesCount,
                readsCount);
        cancel("ClprStreamingSyncCall closed before the exchange completed", null);
    }

    /**
     * Aborts the call. Idempotent.
     *
     * @param message a description of why the call is being canceled
     * @param cause   the triggering throwable, or {@code null}
     */
    public void cancel(@NonNull final String message, @Nullable final Throwable cause) {
        requireNonNull(message);
        if (cancelled) {
            return;
        }
        cancelled = true;
        writeClosed = true;
        if (cause != null) {
            LOG.warn("{} canceled: {}", tag, message, cause);
        } else {
            LOG.debug("{} canceled without an exception cause: {}", tag, message);
        }
        call.cancel(message, cause);
    }

    /**
     * Renders {@code bundle_request} for the logs. The field is one-shot per side per exchange, so this reads
     * {@code absent} on every message after the first — that is the protocol working, not a fault.
     */
    @NonNull
    private static String describeRequest(@NonNull final ClprStreamingSyncPayload payload) {
        final var request = payload.bundleRequest();
        return request == null ? "absent" : "currentReceivedMessageId=" + request.currentReceivedMessageId();
    }
}
