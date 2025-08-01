// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle.disk;

import static com.swirlds.state.merkle.StateUtils.computeLabel;
import static com.swirlds.state.merkle.StateUtils.getStateKeyForQueue;
import static com.swirlds.state.merkle.StateUtils.getStateValue;
import static com.swirlds.state.merkle.logging.StateLogger.logQueueAdd;
import static com.swirlds.state.merkle.logging.StateLogger.logQueueRemove;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.platform.state.QueueState;
import com.hedera.hapi.platform.state.StateValue;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.spi.WritableQueueStateBase;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Iterator;

/**
 * An implementation of {@link com.swirlds.state.spi.WritableQueueState} backed by a {@link VirtualMap}, resulting in a state
 * that is stored on disk.
 *
 * @param <E> The type of element in the queue
 */
public class OnDiskWritableQueueState<E> extends WritableQueueStateBase<E> {

    /** The backing merkle data structure to use */
    @NonNull
    private final VirtualMap virtualMap;

    @NonNull
    private final OnDiskQueueHelper<E> onDiskQueueHelper;

    /**
     * Create a new instance
     *
     * @param serviceName the service name
     * @param stateKey    the state key
     * @param virtualMap  the backing merkle data structure to use
     */
    public OnDiskWritableQueueState(
            @NonNull final String serviceName, @NonNull final String stateKey, @NonNull final VirtualMap virtualMap) {
        super(serviceName, stateKey);
        this.virtualMap = requireNonNull(virtualMap);
        this.onDiskQueueHelper = new OnDiskQueueHelper<>(serviceName, stateKey, virtualMap);
    }

    /** {@inheritDoc} */
    @Override
    protected void addToDataSource(@NonNull E element) {
        QueueState state = onDiskQueueHelper.getState();
        if (state == null) {
            // Adding to this Queue State first time - initialize QueueState.
            state = new QueueState(1, 1);
        }

        final Bytes keyBytes = getStateKeyForQueue(serviceName, stateKey, state.tail());
        final StateValue stateValue = getStateValue(serviceName, stateKey, element);

        virtualMap.put(keyBytes, stateValue, StateValue.PROTOBUF);
        // increment tail and update state
        onDiskQueueHelper.updateState(new QueueState(state.head(), state.tail() + 1));

        // Log to transaction state log, what was added
        logQueueAdd(computeLabel(serviceName, stateKey), element);
    }

    /** {@inheritDoc} */
    @Override
    protected void removeFromDataSource() {
        final QueueState state = requireNonNull(onDiskQueueHelper.getState());
        if (!OnDiskQueueHelper.isEmpty(state)) {
            final StateValue stateValue =
                    virtualMap.remove(getStateKeyForQueue(serviceName, stateKey, state.head()), StateValue.PROTOBUF);
            // increment head and update state
            onDiskQueueHelper.updateState(new QueueState(state.head() + 1, state.tail()));
            final var removedValue = stateValue != null ? stateValue.value().as() : null;
            // Log to transaction state log, what was removed
            logQueueRemove(computeLabel(serviceName, stateKey), removedValue);
        } else {
            // Log to transaction state log, what was removed
            logQueueRemove(computeLabel(serviceName, stateKey), null);
        }
    }

    /** {@inheritDoc} */
    @NonNull
    @Override
    protected Iterator<E> iterateOnDataSource() {
        return onDiskQueueHelper.iterateOnDataSource();
    }
}
