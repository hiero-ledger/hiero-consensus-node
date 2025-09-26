// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.test.fixtures.merkle.singleton;

import static com.swirlds.state.test.fixtures.merkle.logging.TestStateLogger.logSingletonRead;
import static com.swirlds.state.test.fixtures.merkle.logging.TestStateLogger.logSingletonRemove;
import static com.swirlds.state.test.fixtures.merkle.logging.TestStateLogger.logSingletonWrite;
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
