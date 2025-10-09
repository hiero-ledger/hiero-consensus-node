// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.test.fixtures.merkle.singleton;

import static java.util.Objects.requireNonNull;

import com.swirlds.state.spi.WritableSingletonStateBase;
import com.swirlds.state.test.fixtures.merkle.MerkleStateRoot;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * @deprecated This class should be removed together with {@link MerkleStateRoot}.
 */
@Deprecated
public class BackedWritableSingletonState<T> extends WritableSingletonStateBase<T> {

    private final SingletonNode<T> backingStore;

    public BackedWritableSingletonState(
            final int stateId, @NonNull final String label, @NonNull final SingletonNode<T> node) {
        super(stateId, requireNonNull(label));
        this.backingStore = node;
    }

    /** {@inheritDoc} */
    @Override
    protected T readFromDataSource() {
        return backingStore.getValue();
    }

    /** {@inheritDoc} */
    @Override
    protected void putIntoDataSource(@NonNull T value) {
        backingStore.setValue(value);
    }

    /** {@inheritDoc} */
    @Override
    protected void removeFromDataSource() {
        backingStore.setValue(null);
    }
}
