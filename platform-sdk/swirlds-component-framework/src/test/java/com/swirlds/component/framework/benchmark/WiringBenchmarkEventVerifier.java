// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.benchmark;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.Function;

public class WiringBenchmarkEventVerifier implements Function<WiringBenchmarkEvent, WiringBenchmarkEvent> {

    public WiringBenchmarkEventVerifier() {}

    @Override
    @NonNull
    public WiringBenchmarkEvent apply(@NonNull final WiringBenchmarkEvent event) {
        // Pretend like we did verification by sleeping for a few microseconds
        busySleep(2000);
        return event;
    }

    public static void busySleep(long nanos) {
        long elapsed;
        final long startTime = System.nanoTime();
        do {
            elapsed = System.nanoTime() - startTime;
        } while (elapsed < nanos);
    }
}
