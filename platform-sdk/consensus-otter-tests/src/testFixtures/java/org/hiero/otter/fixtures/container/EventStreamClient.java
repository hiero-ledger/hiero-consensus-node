// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.container;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.fail;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.grpc.stub.StreamObserver;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.main.model.NodeId;
import org.hiero.otter.fixtures.container.proto.EventMessage;
import org.hiero.otter.fixtures.container.proto.NodeCommunicationServiceGrpc.NodeCommunicationServiceStub;
import org.hiero.otter.fixtures.container.proto.SubscribeRequest;
import org.hiero.otter.fixtures.container.proto.SyncPoint;

/**
 * Subscribes to a node's event stream and keeps the subscription alive across transport failures.
 *
 * <p>The event stream is the only channel carrying status, log, and consensus-round events from a
 * container node. If it dies silently the fixture's cached view of the node freezes forever. This
 * client guards against that: when the stream ends unexpectedly while the node is still running, it
 * logs a warning and re-subscribes with exponential backoff, resuming after the last sequence number
 * it saw. Only if the stream stays down beyond {@link #MAX_DOWNTIME} does it fail the test, and the
 * failure message reports {@code nodeIsAlive()} so a dead process reads differently from a dead stream.
 */
final class EventStreamClient {

    private static final Logger log = LogManager.getLogger(EventStreamClient.class);

    /** Initial re-subscribe backoff. */
    private static final long INITIAL_BACKOFF_MILLIS = 250;

    /** Maximum re-subscribe backoff. */
    private static final long MAX_BACKOFF_MILLIS = 5_000;

    /** How long the stream may stay down before the test is failed. */
    private static final Duration MAX_DOWNTIME = Duration.ofMinutes(2);

    /** The ID of the node this client subscribes to, used only for logging. */
    private final NodeId selfId;

    /** The asynchronous stub used to open the subscription. */
    private final NodeCommunicationServiceStub stub;

    /** The queue into which received messages are placed for consumption by the fixture. */
    private final BlockingQueue<EventMessage> sink;

    /** Whether the node is still supposed to be running (a stream end is only a problem if it is). */
    private final BooleanSupplier nodeIsRunning;

    /** Whether the node process is still alive, reported in the give-up failure message. */
    private final BooleanSupplier nodeIsAlive;

    /** The sequence number of the last ordered message received, used to resume after a reconnect. */
    private final AtomicLong lastSequence = new AtomicLong(0);

    /** Scheduler running the re-subscribe attempts. */
    private final ScheduledExecutorService scheduler;

    /** Whether this client has been closed. */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /** The current re-subscribe backoff, reset to {@link #INITIAL_BACKOFF_MILLIS} on every message. */
    private final AtomicLong backoffMillis = new AtomicLong(INITIAL_BACKOFF_MILLIS);

    /** {@link System#nanoTime()} when the stream first went down in the current outage, or 0 if up. */
    private volatile long downSinceNanos = 0;

    /**
     * Creates a new {@link EventStreamClient}. Call {@link #start()} to open the subscription.
     *
     * @param selfId the ID of the node this client subscribes to
     * @param stub the asynchronous stub used to open the subscription
     * @param sink the queue into which received messages are placed
     * @param nodeIsRunning supplies whether the node is still supposed to be running
     * @param nodeIsAlive supplies whether the node process is still alive
     */
    EventStreamClient(
            @NonNull final NodeId selfId,
            @NonNull final NodeCommunicationServiceStub stub,
            @NonNull final BlockingQueue<EventMessage> sink,
            @NonNull final BooleanSupplier nodeIsRunning,
            @NonNull final BooleanSupplier nodeIsAlive) {
        this.selfId = requireNonNull(selfId);
        this.stub = requireNonNull(stub);
        this.sink = requireNonNull(sink);
        this.nodeIsRunning = requireNonNull(nodeIsRunning);
        this.nodeIsAlive = requireNonNull(nodeIsAlive);
        final ThreadFactory factory = r -> {
            final Thread t = new Thread(r, "event-stream-client-" + selfId.id());
            t.setDaemon(true);
            return t;
        };
        this.scheduler = Executors.newSingleThreadScheduledExecutor(factory);
    }

    /**
     * Opens the initial subscription.
     */
    void start() {
        subscribe();
    }

    /**
     * Opens (or re-opens) the subscription, resuming after the last sequence number seen.
     */
    private void subscribe() {
        if (closed.get()) {
            return;
        }
        final SubscribeRequest request = SubscribeRequest.newBuilder()
                .setAfterSequence(lastSequence.get())
                .build();
        stub.subscribe(request, new StreamObserver<>() {
            @Override
            public void onNext(final EventMessage message) {
                onMessage(message);
            }

            @Override
            public void onError(final Throwable error) {
                handleStreamEnd(error);
            }

            @Override
            public void onCompleted() {
                handleStreamEnd(null);
            }
        });
    }

    /**
     * Handles a received message: resets the failure state, checks for a data gap, tracks the sequence
     * number, and hands the message off to the sink.
     */
    private void onMessage(@NonNull final EventMessage message) {
        if (closed.get()) {
            return;
        }
        // A message proves the stream is healthy again.
        backoffMillis.set(INITIAL_BACKOFF_MILLIS);
        downSinceNanos = 0;

        if (message.hasSyncPoint()) {
            checkForGap(message.getSyncPoint());
        }
        final long sequence = message.getSequence();
        if (sequence > 0) {
            lastSequence.accumulateAndGet(sequence, Math::max);
        }
        sink.add(message);
    }

    /**
     * Warns if the oldest buffered message on the server is newer than the next message we expect,
     * which means messages were evicted before we could re-subscribe and results may be incomplete.
     */
    private void checkForGap(@NonNull final SyncPoint syncPoint) {
        final long oldestBuffered = syncPoint.getOldestBuffered();
        final long expectedNext = lastSequence.get() + 1;
        if (oldestBuffered > expectedNext) {
            log.warn(
                    "Event stream for node {} lost messages {}..{}; results may be incomplete",
                    selfId,
                    expectedNext,
                    oldestBuffered - 1);
        }
    }

    /**
     * Handles the stream ending, either via an error or an unexpected completion. If the node is still
     * running, logs a warning and either re-subscribes with backoff or, if the stream has been down too
     * long, fails the test.
     *
     * @param error the error that ended the stream, or {@code null} if it completed
     */
    private void handleStreamEnd(@Nullable final Throwable error) {
        if (closed.get() || !nodeIsRunning.getAsBoolean()) {
            // Expected: the client has been closed or the node is shutting down.
            return;
        }

        final long now = System.nanoTime();
        if (downSinceNanos == 0) {
            downSinceNanos = now;
        }
        final Duration downFor = Duration.ofNanos(now - downSinceNanos);

        if (error != null) {
            log.warn("Event stream for node {} failed (down for {}); re-subscribing", selfId, downFor, error);
        } else {
            log.warn(
                    "Event stream for node {} completed unexpectedly while running (down for {}); re-subscribing",
                    selfId,
                    downFor);
        }

        if (downFor.compareTo(MAX_DOWNTIME) > 0) {
            fail(
                    String.format(
                            "Event stream for node %s stayed down for %s (nodeIsAlive=%s)",
                            selfId, downFor, nodeIsAlive.getAsBoolean()),
                    error);
        } else {
            final long delay = backoffMillis.getAndUpdate(current -> Math.min(current * 2, MAX_BACKOFF_MILLIS));
            scheduleResubscribe(delay);
        }
    }

    /**
     * Schedules a re-subscribe attempt after the given delay, unless this client has been closed.
     */
    private void scheduleResubscribe(final long delayMillis) {
        if (closed.get()) {
            return;
        }
        try {
            scheduler.schedule(this::subscribe, delayMillis, TimeUnit.MILLISECONDS);
        } catch (final RejectedExecutionException e) {
            // The scheduler was shut down concurrently with close(); ignore.
        }
    }

    /**
     * Stops this client. Idempotent: after the first call the scheduler is stopped and all later stream
     * callbacks are ignored.
     */
    void close() {
        if (closed.compareAndSet(false, true)) {
            scheduler.shutdownNow();
        }
    }
}
