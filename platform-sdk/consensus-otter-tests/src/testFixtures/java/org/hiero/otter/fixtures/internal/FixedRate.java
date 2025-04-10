// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import org.hiero.otter.fixtures.TransactionGenerator.Rate;

public class FixedRate implements Rate {

    private final int intervalNanos;

    public FixedRate(final int tps) {
        if (tps <= 0) {
            throw new IllegalArgumentException("TPS must be greater than 0");
        }
        this.intervalNanos = 1_000_000_000 / tps;
    }

    @Override
    public long nextDelayNS(@NonNull final Instant start, @NonNull final Instant now) {
        if (start.isAfter(now)) {
            throw new IllegalArgumentException("Start time must be before now");
        }
        final long elapsedNanos = Duration.between(start, now).toNanos();
        return intervalNanos - (elapsedNanos % intervalNanos);
    }
}
