// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.network.protocol;

import com.swirlds.platform.state.signed.ReservedSignedState;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.base.concurrent.BlockingResourceProvider;
import org.hiero.base.concurrent.locks.locked.LockedResource;
import org.hiero.base.exceptions.NotImplementedException;

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
    private final BlockingResourceProvider<Value> provider = new BlockingResourceProvider<>();

    /**
     * Resolves the promise with a reserved signed state.
     *
     * @param value the reserved signed state to provide
     * @throws InterruptedException if the thread is interrupted while providing
     */
    public void resolveWithValue(@NonNull final ReservedSignedState value) throws InterruptedException {
        this.provider.provide(new Value(value, null));
    }

    /**
     * Resolves the promise with an exception.
     *
     * @param exception the exception to throw when resolving provide
     * @throws InterruptedException if the thread is interrupted while providing
     */
    public void resolveWithException(@NonNull final RuntimeException exception) throws InterruptedException {
        this.provider.provide(new Value(null, exception));
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
     * Waits for a value or an exception depending on what was provided.
     *
     * @return the reserved signed state
     * @throws RuntimeException if the thread is interrupted while waiting
     */
    @Nullable
    public ReservedStateOrExceptionResource awaitResolution() throws InterruptedException {
        final var obtainedResource = provider.waitForResource();
        return obtainedResource == null ? null : new ReservedStateOrExceptionResource(obtainedResource);
    }

    private record Value(ReservedSignedState reservedSignedState, RuntimeException throwable) {}

    public static class ReservedStateOrExceptionResource implements LockedResource<ReservedSignedState> {
        private final RuntimeException throwable;
        ReservedSignedState value;

        private ReservedStateOrExceptionResource(LockedResource<Value> lockedResource) {
            this.value = lockedResource.getResource() == null
                    ? null
                    : lockedResource.getResource().reservedSignedState();
            this.throwable = lockedResource.getResource() == null
                    ? null
                    : lockedResource.getResource().throwable();
        }

        @Nullable
        @Override
        public ReservedSignedState getResource() {
            return value;
        }

        @Nullable
        public Exception getException() {
            return throwable;
        }

        @Override
        public void setResource(@Nullable final ReservedSignedState resource) {
            throw new NotImplementedException();
        }

        @Override
        public void close() {
            value.close();
        }
    }
}
