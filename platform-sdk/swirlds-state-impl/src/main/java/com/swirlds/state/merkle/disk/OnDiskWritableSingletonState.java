/*
 * Copyright (C) 2020-2025 Hedera Hashgraph, LLC
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
import static com.swirlds.state.merkle.logging.StateLogger.logSingletonRead;
import static com.swirlds.state.merkle.logging.StateLogger.logSingletonRemove;
import static com.swirlds.state.merkle.logging.StateLogger.logSingletonWrite;
import static java.util.Objects.requireNonNull;

import com.hedera.pbj.runtime.Codec;
import com.swirlds.state.spi.WritableSingletonStateBase;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;

public class OnDiskWritableSingletonState<T> extends WritableSingletonStateBase<T> {

    @NonNull
    private final VirtualMap virtualMap;

    @NonNull
    private final Codec<T> valueCodec;

    public OnDiskWritableSingletonState(
            @NonNull final String serviceName,
            @NonNull final String stateKey,
            @NonNull final Codec<T> valueCodec,
            @NonNull final VirtualMap virtualMap) {
        super(serviceName, stateKey);

        this.valueCodec = requireNonNull(valueCodec);
        this.virtualMap = Objects.requireNonNull(virtualMap);
    }

    /** {@inheritDoc} */
    @Override
    protected T readFromDataSource() {
        final var value = virtualMap.get(getVirtualMapKey(serviceName, stateKey), valueCodec);
        // Log to transaction state log, what was read
        logSingletonRead(computeLabel(serviceName, stateKey), value);
        return value;
    }

    /** {@inheritDoc} */
    @Override
    protected void putIntoDataSource(@NonNull T value) {
        virtualMap.put(getVirtualMapKey(serviceName, stateKey), value, valueCodec);
        // Log to transaction state log, what was put
        logSingletonWrite(computeLabel(serviceName, stateKey), value);
    }

    /** {@inheritDoc} */
    @Override
    protected void removeFromDataSource() {
        final var removed = virtualMap.remove(getVirtualMapKey(serviceName, stateKey), valueCodec);
        // Log to transaction state log, what was removed
        logSingletonRemove(computeLabel(serviceName, stateKey), removed);
    }
}
