// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.otter.docker.app;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.grpc.stub.StreamObserver;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import org.hiero.otter.fixtures.container.proto.EventMessage;
import org.hiero.otter.fixtures.container.proto.EventMessage.EventCase;
import org.hiero.otter.fixtures.container.proto.SyncPoint;

/**
 * A sequence-stamped, bounded ring buffer of {@link EventMessage}s that decouples the production of
 * events from their delivery to a subscriber.
 *
 * <p>Every published message is stamped with a monotonically increasing sequence number and appended
 * to a buffer bounded by both a maximum message count and a maximum total serialized size. Only a
 * single subscription is active at a time: subscribing fences (replaces) any previous subscription,
 * receives a {@link SyncPoint} snapshot, a replay of the buffered messages after a requested
 * sequence, and then live messages as they are published.
 *
 * <p>This design allows a client whose transport hiccuped to re-subscribe and resume without losing
 * events, as long as the events are still buffered.
 */
public final class EventStreamManager {

    /** Default maximum number of buffered messages. */
    static final int DEFAULT_MAX_MESSAGES = 10_000;

    /** Default maximum total serialized size of buffered messages, in bytes (32 MB). */
    static final long DEFAULT_MAX_BYTES = 32L * 1024 * 1024;

    /** Thread name for the dispatcher that delivers messages to the active subscriber. */
    private static final String DISPATCH_THREAD_NAME = "event-stream-dispatcher";

    /** Maximum number of buffered messages before the oldest are evicted. */
    private final int maxMessages;

    /** Maximum total serialized size of buffered messages before the oldest are evicted. */
    private final long maxBytes;

    /** The buffered, sequence-stamped messages, ordered oldest first. */
    private final Deque<EventMessage> buffer = new ArrayDeque<>();

    /** Executor running the active dispatcher loop. Single-threaded: only one subscription is active. */
    private final ExecutorService dispatchExecutor;

    /** Total serialized size of the buffered messages, in bytes. */
    private long bufferBytes = 0;

    /** The sequence number stamped on the most recently published message. */
    private long latestSequence = 0;

    /** The platform's current status, updated whenever a {@link EventCase#PLATFORM_STATUS_CHANGE} is published. */
    private volatile String currentStatus = "";

    /** The dispatcher for the single active subscription, or {@code null} if there is none. */
    @Nullable
    private OutboundDispatcher dispatcher;

    /**
     * Creates a new {@link EventStreamManager} with the default buffer limits.
     */
    public EventStreamManager() {
        this(DEFAULT_MAX_MESSAGES, DEFAULT_MAX_BYTES);
    }

    /**
     * Creates a new {@link EventStreamManager} with the given buffer limits. Package-private for testing.
     *
     * @param maxMessages the maximum number of buffered messages
     * @param maxBytes the maximum total serialized size of buffered messages, in bytes
     */
    EventStreamManager(final int maxMessages, final long maxBytes) {
        if (maxMessages <= 0) {
            throw new IllegalArgumentException("maxMessages must be positive");
        }
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        this.maxMessages = maxMessages;
        this.maxBytes = maxBytes;
        final ThreadFactory factory = r -> {
            final Thread t = new Thread(r, DISPATCH_THREAD_NAME);
            t.setDaemon(true);
            return t;
        };
        this.dispatchExecutor = Executors.newSingleThreadExecutor(factory);
    }

    /**
     * Stamps the given message with the next sequence number, appends it to the buffer (evicting the
     * oldest messages while over either limit), updates the cached current status, and delivers it to
     * the active subscriber if there is one.
     *
     * @param message the message to publish
     */
    public synchronized void publish(@NonNull final EventMessage message) {
        final long sequence = ++latestSequence;
        final EventMessage stamped = message.toBuilder().setSequence(sequence).build();

        buffer.addLast(stamped);
        bufferBytes += stamped.getSerializedSize();
        if (stamped.getEventCase() == EventCase.PLATFORM_STATUS_CHANGE) {
            currentStatus = stamped.getPlatformStatusChange().getNewStatus();
        }
        evictWhileOverLimit();

        if (dispatcher != null) {
            dispatcher.enqueue(stamped);
        }
    }

    /**
     * Installs a new subscription, fencing (replacing) any previous one. The subscriber first receives
     * a {@link SyncPoint} snapshot, then a replay of every buffered message with a sequence number
     * greater than {@code afterSequence}, and then live messages as they are published.
     *
     * @param afterSequence only buffered messages with a sequence number strictly greater than this are replayed
     * @param observer the observer to which messages are delivered
     */
    public synchronized void subscribe(final long afterSequence, @NonNull final StreamObserver<EventMessage> observer) {
        // Fence any previous subscription so that only a single consumer is ever active.
        if (dispatcher != null) {
            dispatcher.shutdown();
        }
        dispatcher = new OutboundDispatcher(dispatchExecutor, observer);

        final long oldestBuffered = buffer.isEmpty() ? 0 : buffer.peekFirst().getSequence();
        final EventMessage syncPoint = EventMessage.newBuilder()
                .setSyncPoint(SyncPoint.newBuilder()
                        .setCurrentStatus(currentStatus)
                        .setOldestBuffered(oldestBuffered)
                        .setLatestSequence(latestSequence)
                        .build())
                .build();
        dispatcher.enqueue(syncPoint);

        for (final EventMessage message : buffer) {
            if (message.getSequence() > afterSequence) {
                dispatcher.enqueue(message);
            }
        }
    }

    /**
     * Evicts the oldest buffered messages while the buffer exceeds either the message-count or the
     * total-byte limit.
     */
    private void evictWhileOverLimit() {
        while (buffer.size() > maxMessages || bufferBytes > maxBytes) {
            final EventMessage removed = buffer.pollFirst();
            if (removed == null) {
                break;
            }
            bufferBytes -= removed.getSerializedSize();
        }
    }
}
