// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.benchmark;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * A quick and dirty simulation of gossip :-). It will generate events like crazy.
 */
public class WiringBenchmarkGossip {
    private final Executor executor;
    private final WiringBenchmarkEventPool eventPool;
    private final Consumer<WiringBenchmarkEvent> toEventVerifier;
    private final AtomicLong eventNumber = new AtomicLong();
    private volatile boolean stopped = false;
    private volatile long checkSum;

    public WiringBenchmarkGossip(
            Executor executor, WiringBenchmarkEventPool eventPool, Consumer<WiringBenchmarkEvent> toEventVerifier) {
        this.executor = executor;
        this.toEventVerifier = toEventVerifier;
        this.eventPool = eventPool;
    }

    public void start() {
        eventNumber.set(0);
        checkSum = 0;
        executor.execute(this::generateEvents);
    }

    private void generateEvents() {
        while (!stopped) {
            final var event = eventPool.checkout(eventNumber.getAndIncrement());
            toEventVerifier.accept(event);
        }
        long lastNumber = eventNumber.get();
        checkSum = lastNumber * (lastNumber + 1) / 2;
    }

    public void stop() {
        stopped = true;
    }

    public long getCheckSum() {
        return checkSum;
    }
}
