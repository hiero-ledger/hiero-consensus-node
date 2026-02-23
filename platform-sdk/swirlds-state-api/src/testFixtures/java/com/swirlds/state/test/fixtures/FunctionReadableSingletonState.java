// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.test.fixtures;

import com.swirlds.state.spi.ReadableSingletonStateBase;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.function.Supplier;

public class FunctionReadableSingletonState<S> extends ReadableSingletonStateBase<S> {

    private final Supplier<S> backingStoreAccessor;

    /**
     * Creates a new instance.
     *
     * @param stateId The state ID
     * @param label The state label
     * @param backingStoreAccessor A {@link Supplier} that provides access to the value in the
     *     backing store.
     */
    public FunctionReadableSingletonState(
            final int stateId, final String label, @NonNull final Supplier<S> backingStoreAccessor) {
        super(stateId, label);
        this.backingStoreAccessor = Objects.requireNonNull(backingStoreAccessor);
    }

    @Override
    protected S readFromDataSource() {
        return backingStoreAccessor.get();
    }
}
