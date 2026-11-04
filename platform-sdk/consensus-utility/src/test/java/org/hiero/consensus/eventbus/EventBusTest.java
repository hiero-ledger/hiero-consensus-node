// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.eventbus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.test.fixtures.eventbus.EventBus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("EventBus Test")
class EventBusTest {

    private static final NodeId TEST_KEY_1 = NodeId.of(1);
    private static final NodeId TEST_KEY_2 = NodeId.of(2);
    private static final String TEST_CHANNEL = "testChannel";
    private static final String TEST_MESSAGE = "testMessage";

    @AfterEach
    void cleanup() {
        EventBus.getInstance(TEST_KEY_1).shutdown();
        EventBus.getInstance(TEST_KEY_2).shutdown();
    }

    @Test
    @DisplayName("Get instance with same key returns same EventBus")
    void getInstanceSameKey() {
        final EventBus bus1 = EventBus.getInstance(TEST_KEY_1);
        final EventBus bus2 = EventBus.getInstance(TEST_KEY_1);

        assertThat(bus2).isSameAs(bus1);
    }

    @Test
    @DisplayName("Get instance with different keys returns different EventBus")
    void getInstanceDifferentKeys() {
        final EventBus bus1 = EventBus.getInstance(TEST_KEY_1);
        final EventBus bus2 = EventBus.getInstance(TEST_KEY_2);

        assertThat(bus2).isNotSameAs(bus1);
    }

    @Test
    @DisplayName("Get instance throws NullPointerException for null key")
    void getInstanceNullKey() {
        assertThatThrownBy(() -> EventBus.getInstance(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Subscribe and publish to single channel")
    void subscribeAndPublish() {
        final EventBus eventBus = EventBus.getInstance(TEST_KEY_1);
        final List<String> receivedMessages = new ArrayList<>();

        eventBus.subscribe(TEST_CHANNEL, receivedMessages::add);
        eventBus.publish(TEST_CHANNEL, TEST_MESSAGE);

        assertThat(receivedMessages).hasSize(1).containsExactly(TEST_MESSAGE);
    }

    @Test
    @DisplayName("Multiple subscribers receive same message")
    void multipleSubscribers() {
        final EventBus eventBus = EventBus.getInstance(TEST_KEY_1);
        final List<String> subscriber1Messages = new ArrayList<>();
        final List<String> subscriber2Messages = new ArrayList<>();

        eventBus.subscribe(TEST_CHANNEL, subscriber1Messages::add);
        eventBus.subscribe(TEST_CHANNEL, subscriber2Messages::add);
        eventBus.publish(TEST_CHANNEL, TEST_MESSAGE);

        assertThat(subscriber1Messages).hasSize(1).containsExactly(TEST_MESSAGE);
        assertThat(subscriber2Messages).hasSize(1).containsExactly(TEST_MESSAGE);
    }

    @Test
    @DisplayName("Subscribe to multiple channels")
    void multipleChannels() {
        final EventBus eventBus = EventBus.getInstance(TEST_KEY_1);
        final List<String> channel1Messages = new ArrayList<>();
        final List<String> channel2Messages = new ArrayList<>();

        eventBus.subscribe("channel1", channel1Messages::add);
        eventBus.subscribe("channel2", channel2Messages::add);

        eventBus.publish("channel1", "message1");
        eventBus.publish("channel2", "message2");

        assertThat(channel1Messages).hasSize(1).containsExactly("message1");
        assertThat(channel2Messages).hasSize(1).containsExactly("message2");
    }

    @Test
    @DisplayName("Publish to channel with no subscribers does nothing")
    void publishNoSubscribers() {
        final EventBus eventBus = EventBus.getInstance(TEST_KEY_1);

        assertThatNoException().isThrownBy(() -> eventBus.publish(TEST_CHANNEL, TEST_MESSAGE));
    }

    @Test
    @DisplayName("Subscribe throws NullPointerException for null channel")
    void subscribeNullChannel() {
        final EventBus eventBus = EventBus.getInstance(TEST_KEY_1);

        assertThatThrownBy(() -> eventBus.subscribe(null, s -> {})).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Subscribe throws NullPointerException for null callback")
    void subscribeNullCallback() {
        final EventBus eventBus = EventBus.getInstance(TEST_KEY_1);

        assertThatThrownBy(() -> eventBus.subscribe(TEST_CHANNEL, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Publish throws NullPointerException for null channel")
    void publishNullChannel() {
        final EventBus eventBus = EventBus.getInstance(TEST_KEY_1);

        assertThatThrownBy(() -> eventBus.publish(null, TEST_MESSAGE)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Publish throws NullPointerException for null message")
    void publishNullMessage() {
        final EventBus eventBus = EventBus.getInstance(TEST_KEY_1);

        assertThatThrownBy(() -> eventBus.publish(TEST_CHANNEL, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Exception in callback does not prevent other callbacks from executing")
    void exceptionInCallback() {
        final EventBus eventBus = EventBus.getInstance(TEST_KEY_1);
        final List<String> messages = new ArrayList<>();

        eventBus.subscribe(TEST_CHANNEL, s -> {
            throw new RuntimeException("Test exception");
        });
        eventBus.subscribe(TEST_CHANNEL, messages::add);

        eventBus.publish(TEST_CHANNEL, TEST_MESSAGE);

        assertThat(messages).hasSize(1).containsExactly(TEST_MESSAGE);
    }

    @Test
    @DisplayName("Shutdown clears all subscribers")
    void shutdown() {
        final EventBus eventBus = EventBus.getInstance(TEST_KEY_1);
        final List<String> messages = new ArrayList<>();

        eventBus.subscribe(TEST_CHANNEL, messages::add);
        eventBus.shutdown();
        eventBus.publish(TEST_CHANNEL, TEST_MESSAGE);

        assertThat(messages).isEmpty();
    }

    @Test
    @DisplayName("Shutdown removes instance from global map")
    void shutdownRemovesInstance() {
        EventBus bus1 = EventBus.getInstance(NodeId.of(3));
        bus1.shutdown();

        EventBus bus2 = EventBus.getInstance(NodeId.of(3));
        assertThat(bus2)
                .as("After shutdown, getInstance should return a new instance")
                .isNotSameAs(bus1);
    }

    @Test
    @DisplayName("EventBus instances are isolated by key")
    void instanceIsolation() {
        final EventBus bus1 = EventBus.getInstance(TEST_KEY_1);
        final EventBus bus2 = EventBus.getInstance(TEST_KEY_2);
        final List<String> messages1 = new ArrayList<>();
        final List<String> messages2 = new ArrayList<>();

        bus1.subscribe(TEST_CHANNEL, messages1::add);
        bus2.subscribe(TEST_CHANNEL, messages2::add);

        bus1.publish(TEST_CHANNEL, "message1");
        bus2.publish(TEST_CHANNEL, "message2");

        assertThat(messages1).hasSize(1).containsExactly("message1");
        assertThat(messages2).hasSize(1).containsExactly("message2");
    }

    @Test
    @DisplayName("Concurrent subscription and publishing")
    void concurrentOperations() throws InterruptedException {
        final EventBus eventBus = EventBus.getInstance(TEST_KEY_1);
        final AtomicInteger counter = new AtomicInteger(0);
        final int threadCount = 10;
        final int messagesPerThread = 100;
        final CountDownLatch latch = new CountDownLatch(threadCount);
        final ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        eventBus.subscribe(TEST_CHANNEL, s -> counter.incrementAndGet());

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < messagesPerThread; j++) {
                        eventBus.publish(TEST_CHANNEL, "message" + j);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        assertThat(counter.get()).isEqualTo(threadCount * messagesPerThread);
    }

    @Test
    @DisplayName("Same callback can be added multiple times")
    void sameCallbackMultipleTimes() {
        final EventBus eventBus = EventBus.getInstance(TEST_KEY_1);
        final AtomicInteger counter = new AtomicInteger(0);
        final Consumer<String> callback = s -> counter.incrementAndGet();

        eventBus.subscribe(TEST_CHANNEL, callback);
        eventBus.subscribe(TEST_CHANNEL, callback);
        eventBus.publish(TEST_CHANNEL, TEST_MESSAGE);

        assertThat(counter.get())
                .as("Same callback added twice should be called twice")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("Verify callback execution order matches subscription order")
    void callbackExecutionOrder() {
        final EventBus eventBus = EventBus.getInstance(TEST_KEY_1);
        final List<Integer> executionOrder = new ArrayList<>();

        eventBus.subscribe(TEST_CHANNEL, s -> executionOrder.add(1));
        eventBus.subscribe(TEST_CHANNEL, s -> executionOrder.add(2));
        eventBus.subscribe(TEST_CHANNEL, s -> executionOrder.add(3));

        eventBus.publish(TEST_CHANNEL, TEST_MESSAGE);

        assertThat(executionOrder).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("Multiple messages to same subscriber")
    void multipleMessagesToSameSubscriber() {
        final EventBus eventBus = EventBus.getInstance(TEST_KEY_1);
        final List<String> messages = new ArrayList<>();

        eventBus.subscribe(TEST_CHANNEL, messages::add);

        eventBus.publish(TEST_CHANNEL, "message1");
        eventBus.publish(TEST_CHANNEL, "message2");
        eventBus.publish(TEST_CHANNEL, "message3");

        assertThat(messages).hasSize(3).containsExactly("message1", "message2", "message3");
    }
}
