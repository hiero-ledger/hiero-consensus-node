// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.otter.docker.app;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.otter.fixtures.container.proto.EventMessage;

/**
 * Handles queuing {@link EventMessage}s and delivering them to a gRPC {@link StreamObserver} on a
 * single background thread.
 */
public final class OutboundDispatcher {

    private static final Logger log = LogManager.getLogger(OutboundDispatcher.class);

    /** Queue used to hand over messages from the platform threads to the dispatcher thread. */
    private final BlockingQueue<EventMessage> outboundQueue = new LinkedBlockingQueue<>();

    /** Indicates whether the dispatcher has been cancelled. */
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    /** Handle to the running dispatcher task so it can be cancelled. */
    private final Future<?> dispatchFuture;

    /**
     * Creates a new dispatcher instance and immediately starts the background task.
     *
     * @param executor the executor used to run the background task
     * @param responseObserver the gRPC stream to which messages will be delivered
     */
    public OutboundDispatcher(
            @NonNull final ExecutorService executor, @NonNull final StreamObserver<EventMessage> responseObserver) {

        // Register a cancellation callback if possible so that we stop delivering messages once the
        // client has canceled the stream.
        if (responseObserver instanceof ServerCallStreamObserver<?> serverObserver) {
            serverObserver.setOnCancelHandler(this::shutdown);
        }

        // Submit the dispatcher loop.
        dispatchFuture = executor.submit(() -> runDispatchLoop(responseObserver));
    }

    /**
     * Adds a message to the outbound queue if the dispatcher has not been cancelled.
     *
     * @param message the message to enqueue
     */
    public void enqueue(@NonNull final EventMessage message) {
        if (!cancelled.get()) {
            outboundQueue.offer(message);
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
                } catch (final RuntimeException e) {
                    // Any exception here implies that the stream is no longer writable.
                    log.error("Unexpected error while sending event message", e);
                    cancelled.set(true);
                }
            }
        } catch (final InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
