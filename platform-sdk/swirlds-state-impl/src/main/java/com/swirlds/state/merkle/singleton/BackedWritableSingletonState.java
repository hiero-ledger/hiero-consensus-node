// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle.singleton;

import static com.swirlds.state.merkle.logging.StateLogger.logSingletonRead;
import static com.swirlds.state.merkle.logging.StateLogger.logSingletonRemove;
import static com.swirlds.state.merkle.logging.StateLogger.logSingletonWrite;
import static java.util.Objects.requireNonNull;

import com.swirlds.state.merkle.MerkleStateRoot;
import com.swirlds.state.spi.WritableSingletonStateBase;
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
        final var value = backingStore.getValue();
        // Log to transaction state log, what was read
        logSingletonRead(label, value);
        return value;
    }

    /** {@inheritDoc} */
    @Override
    protected void putIntoDataSource(@NonNull T value) {
        backingStore.setValue(value);
        // Log to transaction state log, what was put
        logSingletonWrite(label, value);
    }

    /** {@inheritDoc} */
    @Override
    protected void removeFromDataSource() {
        final var removed = backingStore.getValue();
        backingStore.setValue(null);
        // Log to transaction state log, what was removed
        logSingletonRemove(label, removed);
    }
}
