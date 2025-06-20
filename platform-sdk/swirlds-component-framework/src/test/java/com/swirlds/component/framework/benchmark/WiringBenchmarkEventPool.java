// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.benchmark;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public final class WiringBenchmarkEventPool {
    private final BlockingQueue<WiringBenchmarkEvent> pool = new LinkedBlockingQueue<>();

    public WiringBenchmarkEventPool() {}

    @NonNull
    public WiringBenchmarkEvent checkout(long number) {
        WiringBenchmarkEvent event = pool.poll();
        if (event == null) {
            event = new WiringBenchmarkEvent();
        }
        event.reset(number);
        return event;
    }

    public void checkin(WiringBenchmarkEvent event) {
        pool.add(event);
    }
}
