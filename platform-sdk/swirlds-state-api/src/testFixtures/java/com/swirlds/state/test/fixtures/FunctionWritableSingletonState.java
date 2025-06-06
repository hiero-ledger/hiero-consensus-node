// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.test.fixtures;

import com.swirlds.state.spi.WritableSingletonStateBase;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class FunctionWritableSingletonState<S> extends WritableSingletonStateBase<S> {

    private final Supplier<S> backingStoreAccessor;

    private final Consumer<S> backingStoreMutator;

    /**
     * Creates a new instance.
     *
     * @param serviceName The name of the service that owns the state.
     * @param stateKey The state key for this instance.
     * @param backingStoreAccessor A {@link Supplier} that provides access to the value in the
     *     backing store.
     * @param backingStoreMutator A {@link Consumer} for mutating the value in the backing store.
     */
    public FunctionWritableSingletonState(
            @NonNull final String serviceName,
            @NonNull final String stateKey,
            @NonNull final Supplier<S> backingStoreAccessor,
            @NonNull final Consumer<S> backingStoreMutator) {
        super(serviceName, stateKey);
        this.backingStoreAccessor = Objects.requireNonNull(backingStoreAccessor);
        this.backingStoreMutator = Objects.requireNonNull(backingStoreMutator);
    }

    @Override
    protected S readFromDataSource() {
        return backingStoreAccessor.get();
    }

    @Override
    protected void putIntoDataSource(@NonNull S value) {
        backingStoreMutator.accept(value);
    }

    @Override
    protected void removeFromDataSource() {
        backingStoreMutator.accept(null);
    }
}
