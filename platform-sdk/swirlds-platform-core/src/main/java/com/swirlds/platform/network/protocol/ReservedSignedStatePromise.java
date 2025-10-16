// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.network.protocol;

import com.swirlds.platform.state.signed.ReservedSignedState;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.base.concurrent.BlockingResourceProvider;
import org.hiero.base.concurrent.locks.locked.LockedResource;

/**
 * This class wraps a {@link BlockingResourceProvider} to manage access to a {@link ReservedSignedState}.
 * This allows a single consumer of {@link ReservedSignedState} to wait for a value that can be provided by only one of multiple producers.
 * Producers are required to request permits in order to provide a value to the consumer with acquire, and release the permit
 * in case they fail to provide a value with release so that other producers might be able to do so.
 * Consumers can await for the value to be provided, blocking until so.
 */
public class ReservedSignedStatePromise {
    /**
     * The underlying blocking resource provider for the reserved signed state
     */
    private final BlockingResourceProvider<ReservedSignedState> provider = new BlockingResourceProvider<>();

    /**
     * Provides a reserved signed state to waiting consumers.
     *
     * @param currentReservedSignedState the reserved signed state to provide
     * @throws InterruptedException if the thread is interrupted while providing
     */
    public void provide(@NonNull final ReservedSignedState currentReservedSignedState) throws InterruptedException {
        this.provider.provide(currentReservedSignedState);
    }

    /**
     * Attempts to acquire a permit to provide a resource.
     *
     * @return true if the permit was successfully acquired, false otherwise
     */
    public boolean acquire() {
        return provider.acquireProvidePermit();
    }

    /**
     * Attempts to block further permits.
     *
     * @return true if the block was successful, false otherwise
     */
    public boolean tryBlock() {
        return provider.tryBlockProvidePermit();
    }

    /**
     * Releases a previously acquired provide permit.
     */
    public void release() {
        provider.releaseProvidePermit();
    }

    /**
     * Waits for and retrieves the reserved signed state.
     *
     * @return the reserved signed state
     * @throws RuntimeException if the thread is interrupted while waiting
     */
    @Nullable
    public LockedResource<ReservedSignedState> await() throws InterruptedException {
        return provider.waitForResource();
    }
}
