// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.otter.docker.app;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.util.EnumMap;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.otter.fixtures.container.proto.EventMessage;
import org.hiero.otter.fixtures.container.proto.EventMessage.EventCase;

/**
 * Handles queuing {@link EventMessage}s and delivering them to a gRPC {@link StreamObserver} on a
 * single background thread.
 */
public final class OutboundDispatcher {

    private static final Logger LOGGER = LogManager.getLogger(OutboundDispatcher.class);

    /** Queue used to hand over messages from the platform threads to the dispatcher thread. */
    private final BlockingDeque<EventMessage> outboundQueue = new LinkedBlockingDeque<>();

    /** Indicates whether the dispatcher has been cancelled. */
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    /** Handle to the running dispatcher task so it can be cancelled. */
    private final Future<?> dispatchFuture;

    /** Counter for each EventCase type */
    private final EnumMap<EventCase, LongAdder> eventCounters = new EnumMap<>(EventCase.class);

    /** Total number of messages enqueued */
    private final LongAdder totalMessages = new LongAdder();

    /**
     * Creates a new dispatcher instance and immediately starts the background task.
     *
     * @param executor the executor used to run the background task
     * @param responseObserver the gRPC stream to which messages will be delivered
     */
    public OutboundDispatcher(
            @NonNull final ExecutorService executor, @NonNull final StreamObserver<EventMessage> responseObserver) {

        // Initialize counters
        for (final EventCase ec : EventCase.values()) {
            eventCounters.put(ec, new LongAdder());
        }

        // Register a cancellation callback if possible so that we stop delivering messages once the
        // client has canceled the stream.
        if (responseObserver instanceof ServerCallStreamObserver<?> serverObserver) {
            serverObserver.setOnCancelHandler(this::shutdown);
        }

        // Submit the dispatcher loop.
        dispatchFuture = executor.submit(() -> runDispatchLoop(responseObserver));
    }

    /**
     * Adds a message to the outbound queue. Platform status changes are inserted at the front of
     * the queue so they are delivered with priority over potentially many log or consensus round
     * messages. This prevents long delays in the test framework that relies on timely status
     * updates.
     *
     * @param message the message to enqueue
     */
    public void enqueue(@NonNull final EventMessage message) {
        if (cancelled.get()) {
            return;
        }

        final EventCase type = message.getEventCase();
        // Count and log
        eventCounters.get(type).increment();
        totalMessages.increment();

        if (totalMessages.sum() % 1000 == 0) {
            final String countsSnapshot = eventCounters.entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue().sum())
                    .collect(Collectors.joining(", "));
            LOGGER.info("Total msgs: {}, per type: {}", totalMessages.sum(), countsSnapshot);
        }

        if (type == EventCase.PLATFORM_STATUS_CHANGE) {
            LOGGER.info(
                    "Enqueuing platform status change ({}, {}): {}", cancelled.get(), outboundQueue.size(), message);
            outboundQueue.offerFirst(message);
        } else {
            outboundQueue.offerLast(message);
        }
    }

    /**
     * Indicates whether the dispatcher has been cancelled.
     *
     * @return {@code true} if the dispatcher has been cancelled, {@code false} otherwise
     */
    public boolean isCancelled() {
        return cancelled.get();
    }

    /**
     * Stops the dispatcher and clears all pending messages.
     */
    public void shutdown() {
        if (cancelled.compareAndSet(false, true)) {
            dispatchFuture.cancel(true);
            outboundQueue.clear();
        }
    }

    /**
     * Continuously takes messages from the queue and delivers them to the gRPC stream.
     */
    private void runDispatchLoop(@NonNull final StreamObserver<EventMessage> observer) {
        try {
            while (!cancelled.get()) {
                final EventMessage msg = outboundQueue.take();
                try {
                    observer.onNext(msg);
                    if (msg.getEventCase() == EventCase.PLATFORM_STATUS_CHANGE) {
                        LOGGER.info("Dispatched platform status change: {}", msg);
                    }
                } catch (final RuntimeException e) {
                    // Any exception here implies that the stream is no longer writable.
                    LOGGER.error("Unexpected error while sending event message", e);
                    cancelled.set(true);
                }
            }
        } catch (final InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
