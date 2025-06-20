// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.benchmark;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.Function;

public class WiringBenchmarkTopologicalEventSorter implements Function<WiringBenchmarkEvent, WiringBenchmarkEvent> {
    private static final int PRINT_FREQUENCY = 10_000_000;
    private long lastTimestamp;
    private long checkSum;

    public WiringBenchmarkTopologicalEventSorter() {
        this.checkSum = 0;
    }

    @NonNull
    @Override
    public WiringBenchmarkEvent apply(@NonNull final WiringBenchmarkEvent event) {
        final long number = event.number();
        checkSum += number + 1; // make 0 contribute to the sum
        if (number % PRINT_FREQUENCY == 0) {
            long curTimestamp = System.currentTimeMillis();
            if (number != 0) {
                System.out.format(
                        "Handled %d events, TPS: %d%n",
                        number, PRINT_FREQUENCY * 1000L / (curTimestamp - lastTimestamp));
            }
            lastTimestamp = curTimestamp;
        }
        return event;
    }

    public long getCheckSum() {
        return checkSum;
    }
}
