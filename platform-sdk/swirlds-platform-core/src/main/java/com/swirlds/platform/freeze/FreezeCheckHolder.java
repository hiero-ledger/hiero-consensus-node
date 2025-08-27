// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.freeze;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/**
 * Holds a reference to the freeze check. Since the freeze check is not available at boot time, this class
 * allows the freeze check to be set later.
 */
public class FreezeCheckHolder implements FreezePeriodChecker, Predicate<Instant> {
    private final AtomicReference<Predicate<Instant>> freezeCheckRef = new AtomicReference<>();

    @Override
    public boolean isInFreezePeriod(@NonNull final Instant timestamp) {
        final Predicate<Instant> isInFreezePeriod = freezeCheckRef.get();
        if (isInFreezePeriod == null) {
            throw new IllegalStateException("A freeze check has not been provided to the holder");
        }
        return isInFreezePeriod.test(Objects.requireNonNull(timestamp));
    }

    /**
     * Sets the freeze check reference atomically.
     *
     * @param freezeCheckRef
     * 		the freeze check reference to set
     */
    public void setFreezeCheckRef(@NonNull final Predicate<Instant> freezeCheckRef) {
        this.freezeCheckRef.set(Objects.requireNonNull(freezeCheckRef));
    }

    @Override
    public boolean test(@NonNull final Instant instant) {
        return isInFreezePeriod(instant);
    }
}
