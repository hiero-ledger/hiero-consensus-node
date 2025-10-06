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
     * @param stateId The state ID
     * @param label The state label
     * @param backingStoreAccessor A {@link Supplier} that provides access to the value in the
     *     backing store
     * @param backingStoreMutator A {@link Consumer} for mutating the value in the backing store
     */
    public FunctionWritableSingletonState(
            final int stateId,
            final String label,
            @NonNull final Supplier<S> backingStoreAccessor,
            @NonNull final Consumer<S> backingStoreMutator) {
        super(stateId, label);
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
