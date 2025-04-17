package com.swirlds.platform.freeze;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

public class FreezeCheckHolder implements FreezePeriodChecker, Predicate<Instant> {
    private final AtomicReference<Predicate<Instant>> freezeCheckRef = new AtomicReference<>();

    @Override
    public boolean isInFreezePeriod(final Instant timestamp) {
        final Predicate<Instant> isInFreezePeriod = freezeCheckRef.get();
        if (isInFreezePeriod == null) {
            throw new IllegalStateException("A freeze check has not been provided to the holder");
        }
        return isInFreezePeriod.test(timestamp);
    }

    public void setFreezeCheckRef(final Predicate<Instant> freezeCheckRef) {
        this.freezeCheckRef.set(freezeCheckRef);
    }

    @Override
    public boolean test(final Instant instant) {
        return isInFreezePeriod(instant);
    }
}
