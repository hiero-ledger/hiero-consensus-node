// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle.singleton;

import static com.swirlds.state.merkle.StateUtils.computeLabel;
import static com.swirlds.state.merkle.logging.StateLogger.logSingletonRead;

import com.swirlds.state.merkle.MerkleStateRoot;
import com.swirlds.state.spi.ReadableSingletonStateBase;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * @deprecated This class should be removed together with {@link MerkleStateRoot}.
 */
@Deprecated
public class BackedReadableSingletonState<T> extends ReadableSingletonStateBase<T> {

    private final SingletonNode<T> backingStore;

    public BackedReadableSingletonState(
            @NonNull final String serviceName, @NonNull final String stateKey, @NonNull final SingletonNode<T> node) {
        super(serviceName, stateKey);
        this.backingStore = node;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected T readFromDataSource() {
        final var value = backingStore.getValue();
        // Log to transaction state log, what was read
        logSingletonRead(computeLabel(serviceName, stateKey), value);
        return value;
    }
}
