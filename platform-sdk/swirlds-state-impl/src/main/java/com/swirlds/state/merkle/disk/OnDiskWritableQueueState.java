/*
 * Copyright (C) 2023-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.swirlds.state.merkle.disk;

import static com.swirlds.state.merkle.StateUtils.computeLabel;
import static com.swirlds.state.merkle.StateUtils.getVirtualMapKey;
import static com.swirlds.state.merkle.logging.StateLogger.logQueueAdd;
import static com.swirlds.state.merkle.logging.StateLogger.logQueueRemove;
import static java.util.Objects.requireNonNull;

import com.hedera.pbj.runtime.Codec;
import com.swirlds.state.merkle.queue.QueueNode;
import com.swirlds.state.merkle.queue.QueueState;
import com.swirlds.state.spi.WritableQueueStateBase;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Iterator;
import java.util.Objects;

/**
 * An implementation of {@link com.swirlds.state.spi.WritableQueueState} based on {@link QueueNode}.
 * @param <E> The type of element in the queue
 */
public class OnDiskWritableQueueState<E> extends WritableQueueStateBase<E> {

    @NonNull
    private final VirtualMap virtualMap;

    @NonNull
    private final Codec<E> valueCodec;

    @NonNull
    private final OnDiskQueueHelper<E> queueHelper;

    public OnDiskWritableQueueState(
            @NonNull final String serviceName,
            @NonNull final String stateKey,
            @NonNull final Codec<E> valueCodec,
            @NonNull final VirtualMap virtualMap) {
        super(serviceName, stateKey);

        this.virtualMap = Objects.requireNonNull(virtualMap);
        this.valueCodec = requireNonNull(valueCodec);
        this.queueHelper = new OnDiskQueueHelper<>(serviceName, stateKey, virtualMap, valueCodec);
    }

    @Override
    protected void addToDataSource(@NonNull E element) {
        final QueueState state = queueHelper.getState();
        virtualMap.put(getVirtualMapKey(serviceName, stateKey, state.getTailAndIncrement()), element, valueCodec);
        queueHelper.updateState(state);
        // Log to transaction state log, what was added
        logQueueAdd(computeLabel(serviceName, stateKey), element);
    }

    @Override
    protected void removeFromDataSource() {
        final QueueState state = queueHelper.getState();
        if (!state.isEmpty()) {
            final var valueToRemove =
                    virtualMap.remove(getVirtualMapKey(serviceName, stateKey, state.getHeadAndIncrement()), valueCodec);
            queueHelper.updateState(state);
            // Log to transaction state log, what was added
            logQueueRemove(computeLabel(serviceName, stateKey), valueToRemove);
        } else {
            // TODO: double check, this is according to the logic in `15090-D-fcqueue-to-virtualmap`
            // Log to transaction state log, what was added
            logQueueRemove(computeLabel(serviceName, stateKey), null);
        }
    }

    @NonNull
    @Override
    protected Iterator<E> iterateOnDataSource() {
        return queueHelper.iterateOnDataSource();
    }
}
