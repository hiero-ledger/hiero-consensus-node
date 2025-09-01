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
     * @param serviceName The name of the service that owns the state.
     * @param stateId The state ID for this instance.
     * @param backingStoreAccessor A {@link Supplier} that provides access to the value in the
     *     backing store.
     */
    public FunctionReadableSingletonState(
            @NonNull final String serviceName, final int stateId, @NonNull final Supplier<S> backingStoreAccessor) {
        super(serviceName, stateId);
        this.backingStoreAccessor = Objects.requireNonNull(backingStoreAccessor);
    }

    @Override
    protected S readFromDataSource() {
        return backingStoreAccessor.get();
    }
}
