// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.otter.docker.app;

import static org.assertj.core.api.Assertions.assertThat;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.grpc.stub.StreamObserver;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.hiero.otter.fixtures.container.proto.EventMessage;
import org.hiero.otter.fixtures.container.proto.EventMessage.EventCase;
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
        assertThat(syncPoint.getEventCase()).isEqualTo(EventCase.SYNC_POINT);

        manager.publish(logMessage("a"));
        manager.publish(logMessage("b"));

        final EventMessage first = observer.poll();
        final EventMessage second = observer.poll();
        assertThat(first.getSequence()).isEqualTo(1);
        assertThat(first.getLogEntry().getMessage()).isEqualTo("a");
        assertThat(second.getSequence()).isEqualTo(2);
        assertThat(second.getLogEntry().getMessage()).isEqualTo("b");
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
        assertThat(syncPoint.getEventCase()).isEqualTo(EventCase.SYNC_POINT);

        // Only messages with sequence > 2 are replayed.
        assertThat(observer.poll().getSequence()).isEqualTo(3);
        assertThat(observer.poll().getSequence()).isEqualTo(4);
        assertThat(observer.poll().getSequence()).isEqualTo(5);
    }

    @Test
    void syncPointCarriesCurrentStatus() throws InterruptedException {
        final EventStreamManager manager = new EventStreamManager();
        manager.publish(logMessage("before status"));
        manager.publish(statusMessage("ACTIVE"));

        final RecordingObserver observer = new RecordingObserver();
        manager.subscribe(0, observer);

        final SyncPoint syncPoint = observer.poll().getSyncPoint();
        assertThat(syncPoint.getCurrentStatus()).isEqualTo("ACTIVE");
        assertThat(syncPoint.getOldestBuffered()).isEqualTo(1);
        assertThat(syncPoint.getLatestSequence()).isEqualTo(2);
    }

    @Test
    void newSubscriptionFencesThePreviousOne() throws InterruptedException {
        final EventStreamManager manager = new EventStreamManager();

        final RecordingObserver first = new RecordingObserver();
        manager.subscribe(0, first);
        // Drain the first subscription's sync point.
        assertThat(first.poll().getEventCase()).isEqualTo(EventCase.SYNC_POINT);

        final RecordingObserver second = new RecordingObserver();
        manager.subscribe(0, second);
        assertThat(second.poll().getEventCase()).isEqualTo(EventCase.SYNC_POINT);

        manager.publish(logMessage("after fencing"));

        // Only the active (second) subscription receives the live message.
        final EventMessage delivered = second.poll();
        assertThat(delivered.getLogEntry().getMessage()).isEqualTo("after fencing");

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

        final SyncPoint syncPoint = observer.poll().getSyncPoint();
        // The two oldest (sequences 1 and 2) were evicted.
        assertThat(syncPoint.getOldestBuffered()).isEqualTo(3);
        assertThat(observer.poll().getSequence()).isEqualTo(3);
        assertThat(observer.poll().getSequence()).isEqualTo(4);
        assertThat(observer.poll().getSequence()).isEqualTo(5);
    }

    @Test
    void evictsOldestByTotalBytes() throws InterruptedException {
        // Size a stamped message so we can bound the buffer to exactly three of them.
        final int stampedSize =
                logMessage("payload").toBuilder().setSequence(1).build().getSerializedSize();
        final EventStreamManager manager = new EventStreamManager(Integer.MAX_VALUE, 3L * stampedSize);
        for (int i = 1; i <= 5; i++) {
            manager.publish(logMessage("payload"));
        }

        final RecordingObserver observer = new RecordingObserver();
        manager.subscribe(0, observer);

        final SyncPoint syncPoint = observer.poll().getSyncPoint();
        // Only the three most recent messages fit within the byte budget.
        assertThat(syncPoint.getOldestBuffered()).isEqualTo(3);
        assertThat(observer.poll().getSequence()).isEqualTo(3);
        assertThat(observer.poll().getSequence()).isEqualTo(4);
        assertThat(observer.poll().getSequence()).isEqualTo(5);
    }

    @NonNull
    private static EventMessage logMessage(@NonNull final String message) {
        return EventMessage.newBuilder()
                .setLogEntry(LogEntry.newBuilder().setMessage(message).build())
                .build();
    }

    @NonNull
    private static EventMessage statusMessage(@NonNull final String status) {
        return EventMessage.newBuilder()
                .setPlatformStatusChange(
                        PlatformStatusChange.newBuilder().setNewStatus(status).build())
                .build();
    }

    /**
     * A {@link StreamObserver} that records delivered messages into a queue for assertion.
     */
    private static final class RecordingObserver implements StreamObserver<EventMessage> {

        private final BlockingQueue<EventMessage> received = new LinkedBlockingQueue<>();

        @Override
        public void onNext(final EventMessage value) {
            received.add(value);
        }

        @Override
        public void onError(final Throwable t) {
            // no-op; tests assert on delivered messages
        }

        @Override
        public void onCompleted() {
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
