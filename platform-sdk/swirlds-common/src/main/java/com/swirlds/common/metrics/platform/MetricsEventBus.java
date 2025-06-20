// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics.platform;

import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * The {@code MetricsEventBus} is an intermediate solution to organize the communication and decouple components
 * until we have defined a more general, final solution.
 *
 * @param <T>
 * 		the event-class
 */
public class MetricsEventBus<T> {

    private final Executor executor;
    private final Queue<Consumer<? super T>> subscribers = new ConcurrentLinkedQueue<>();

    /**
     * Constructor of {@code MetricsEventBus}
     *
     * @param executor
     * 		An {@link Executor} that is used to notify subscribers
     * @throws NullPointerException in case {@code executor} parameter is {@code null}
     */
    public MetricsEventBus(final Executor executor) {
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
    }

    /**
     * Subscribe a new subscriber
     * <p>
     * The new subscriber will receive the given previous events after subscription.
     *
     * @param subscriber
     * 		The new {@code subscriber}
     * @param previousEvents
     * 		A {@link Supplier} of previous events. To ensure, that we do not miss events, this will be evaluated
     * 		after the subscriber was added.
     * @return a {@link Runnable} with which the subscriber can be unsubscribed
     * @throws NullPointerException if any of the following parameters are {@code null}.
     *     <ul>
     *       <li>{@code subscriber}</li>
     *       <li>{@code previousEvents}</li>
     *     </ul>
     */
    public Runnable subscribe(final Consumer<? super T> subscriber, final Supplier<Stream<T>> previousEvents) {
        Objects.requireNonNull(subscriber, "subscriber must not be null");
        Objects.requireNonNull(previousEvents, "previousEvents must not be null");
        subscribers.add(subscriber);
        executor.execute(() -> previousEvents.get().forEach(subscriber));
        return () -> subscribers.remove(subscriber);
    }

    /**
     * Submit a new event.
     * <p>
     * This method will return immediately. The subscribers using the {@link Executor} of this class
     *
     * @param event
     * 		The Event that will be sent to all subscribers
     * @throws NullPointerException in case {@code event} parameter is {@code null}
     */
    public void submit(final T event) {
        Objects.requireNonNull(event, "event must not be null");
        executor.execute(() -> subscribers.forEach(subscriber -> subscriber.accept(event)));
    }
}
