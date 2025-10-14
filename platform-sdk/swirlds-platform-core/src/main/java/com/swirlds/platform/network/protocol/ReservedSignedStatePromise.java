// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.network.protocol;

import com.swirlds.platform.state.signed.ReservedSignedState;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.base.concurrent.BlockingResourceProvider;

/**
 * A promise for a reserved signed state that allows coordination between providers and consumers.
 * This class wraps a {@link BlockingResourceProvider} to manage access to a {@link ReservedSignedState}.
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
     * Attempts to block further provide permits.
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
    public ReservedSignedState await() {
        try (final var lock = provider.waitForResource()) {
            return lock.getResource();
        } catch (final InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
