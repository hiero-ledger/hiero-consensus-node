// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.otter.docker.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.pbj.runtime.grpc.Pipeline;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.hiero.otter.fixtures.container.proto.EventMessage;
import org.hiero.otter.fixtures.container.proto.EventMessage.EventOneOfType;
import org.hiero.otter.fixtures.container.proto.LogEntry;
import org.hiero.otter.fixtures.container.proto.PlatformStatusChange;
import org.hiero.otter.fixtures.container.proto.SyncPoint;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link EventStreamManager}.
 */
class EventStreamManagerTest {

    /** How long a poll for an expected message waits before giving up. */
    private static final long POLL_TIMEOUT_SECONDS = 5;

    @Test
    void stampsSequencesAndDeliversLiveMessages() throws InterruptedException {
        final EventStreamManager manager = new EventStreamManager();
        final RecordingObserver observer = new RecordingObserver();

        manager.subscribe(0, observer);

        // First message is always a sync point.
        final EventMessage syncPoint = observer.poll();
        assertThat(syncPoint.event().kind()).isEqualTo(EventOneOfType.SYNC_POINT);

        manager.publish(logMessage("a"));
        manager.publish(logMessage("b"));

        final EventMessage first = observer.poll();
        final EventMessage second = observer.poll();
        assertThat(first.sequence()).isEqualTo(1);
        assertThat(first.logEntryOrThrow().message()).isEqualTo("a");
        assertThat(second.sequence()).isEqualTo(2);
        assertThat(second.logEntryOrThrow().message()).isEqualTo("b");
    }

    @Test
    void replaysBufferedMessagesAfterRequestedSequence() throws InterruptedException {
        final EventStreamManager manager = new EventStreamManager();
        for (int i = 1; i <= 5; i++) {
            manager.publish(logMessage("msg-" + i));
        }

        final RecordingObserver observer = new RecordingObserver();
        manager.subscribe(2, observer);

        final EventMessage syncPoint = observer.poll();
        assertThat(syncPoint.event().kind()).isEqualTo(EventOneOfType.SYNC_POINT);

        // Only messages with sequence > 2 are replayed.
        assertThat(observer.poll().sequence()).isEqualTo(3);
        assertThat(observer.poll().sequence()).isEqualTo(4);
        assertThat(observer.poll().sequence()).isEqualTo(5);
    }

    @Test
    void syncPointCarriesCurrentStatus() throws InterruptedException {
        final EventStreamManager manager = new EventStreamManager();
        manager.publish(logMessage("before status"));
        manager.publish(statusMessage("ACTIVE"));

        final RecordingObserver observer = new RecordingObserver();
        manager.subscribe(0, observer);

        final SyncPoint syncPoint = observer.poll().syncPointOrThrow();
        assertThat(syncPoint.currentStatus()).isEqualTo("ACTIVE");
        assertThat(syncPoint.oldestBuffered()).isEqualTo(1);
        assertThat(syncPoint.latestSequence()).isEqualTo(2);
    }

    @Test
    void newSubscriptionFencesThePreviousOne() throws InterruptedException {
        final EventStreamManager manager = new EventStreamManager();

        final RecordingObserver first = new RecordingObserver();
        manager.subscribe(0, first);
        // Drain the first subscription's sync point.
        assertThat(first.poll().event().kind()).isEqualTo(EventOneOfType.SYNC_POINT);

        final RecordingObserver second = new RecordingObserver();
        manager.subscribe(0, second);
        assertThat(second.poll().event().kind()).isEqualTo(EventOneOfType.SYNC_POINT);

        manager.publish(logMessage("after fencing"));

        // Only the active (second) subscription receives the live message.
        final EventMessage delivered = second.poll();
        assertThat(delivered.logEntryOrThrow().message()).isEqualTo("after fencing");

        // The fenced (first) subscription receives nothing further.
        assertThat(first.pollNoMessage()).isTrue();
    }

    @Test
    void evictsOldestByMessageCount() throws InterruptedException {
        final EventStreamManager manager = new EventStreamManager(3, EventStreamManager.DEFAULT_MAX_BYTES);
        for (int i = 1; i <= 5; i++) {
            manager.publish(logMessage("msg-" + i));
        }

        final RecordingObserver observer = new RecordingObserver();
        manager.subscribe(0, observer);

        final SyncPoint syncPoint = observer.poll().syncPointOrThrow();
        // The two oldest (sequences 1 and 2) were evicted.
        assertThat(syncPoint.oldestBuffered()).isEqualTo(3);
        assertThat(observer.poll().sequence()).isEqualTo(3);
        assertThat(observer.poll().sequence()).isEqualTo(4);
        assertThat(observer.poll().sequence()).isEqualTo(5);
    }

    @Test
    void evictsOldestByTotalBytes() throws InterruptedException {
        // Size a stamped message so we can bound the buffer to exactly three of them.
        final int stampedSize =
                logMessage("payload").copyBuilder().sequence(1).build().protobufSize();
        final EventStreamManager manager = new EventStreamManager(Integer.MAX_VALUE, 3L * stampedSize);
        for (int i = 1; i <= 5; i++) {
            manager.publish(logMessage("payload"));
        }

        final RecordingObserver observer = new RecordingObserver();
        manager.subscribe(0, observer);

        final SyncPoint syncPoint = observer.poll().syncPointOrThrow();
        // Only the three most recent messages fit within the byte budget.
        assertThat(syncPoint.oldestBuffered()).isEqualTo(3);
        assertThat(observer.poll().sequence()).isEqualTo(3);
        assertThat(observer.poll().sequence()).isEqualTo(4);
        assertThat(observer.poll().sequence()).isEqualTo(5);
    }

    @NonNull
    private static EventMessage logMessage(@NonNull final String message) {
        return EventMessage.newBuilder()
                .logEntry(LogEntry.newBuilder().message(message).build())
                .build();
    }

    @NonNull
    private static EventMessage statusMessage(@NonNull final String status) {
        return EventMessage.newBuilder()
                .platformStatusChange(
                        PlatformStatusChange.newBuilder().newStatus(status).build())
                .build();
    }

    /**
     * A {@link Pipeline} that records delivered messages into a queue for assertion.
     */
    private static final class RecordingObserver implements Pipeline<EventMessage> {

        private final BlockingQueue<EventMessage> received = new LinkedBlockingQueue<>();

        @Override
        public void onSubscribe(final Flow.Subscription subscription) {
            // no-op; the manager delivers messages without waiting for demand
        }

        @Override
        public void onNext(final EventMessage value) {
            received.add(value);
        }

        @Override
        public void onError(final Throwable t) {
            // no-op; tests assert on delivered messages
        }

        @Override
        public void onComplete() {
            // no-op
        }

        /**
         * Waits for and returns the next delivered message, failing if none arrives in time.
         */
        @NonNull
        EventMessage poll() throws InterruptedException {
            final EventMessage message = received.poll(POLL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(message).as("expected a message to be delivered").isNotNull();
            return message;
        }

        /**
         * Returns {@code true} if no message is delivered within a short window.
         */
        boolean pollNoMessage() throws InterruptedException {
            return received.poll(500, TimeUnit.MILLISECONDS) == null;
        }
    }
}
