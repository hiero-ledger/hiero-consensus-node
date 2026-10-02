// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.util.function.LongSupplier;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Publishes a best-effort block-full signal from handle to ingest. Only the handle thread writes it.
 * Expiry reopens ingest without changing the authoritative consensus-time throttle.
 */
@Singleton
public class BlockSizeIngestGate {
    private final LongSupplier nanoTime;

    @Nullable
    private volatile Long fullSinceNanos;

    @Inject
    public BlockSizeIngestGate() {
        this(System::nanoTime);
    }

    BlockSizeIngestGate(@NonNull final LongSupplier nanoTime) {
        this.nanoTime = requireNonNull(nanoTime);
    }

    /** Clears the signal when handle opens a new block. */
    public void onBlockStarted() {
        fullSinceNanos = null;
    }

    /** Publishes the first threshold crossing; repeated observations never extend the timeout. */
    public void onBlockSizeLimitReached() {
        if (fullSinceNanos == null) {
            fullSinceNanos = nanoTime.getAsLong();
        }
    }

    /** Returns whether the signal is fresh. Zero disables the gate; stale signals fail open. */
    public boolean shouldReject(@NonNull final Duration maxAge) {
        final var observedFullSince = fullSinceNanos;
        return observedFullSince != null
                && !maxAge.isNegative()
                && !maxAge.isZero()
                && Duration.ofNanos(nanoTime.getAsLong() - observedFullSince).compareTo(maxAge) < 0;
    }
}
