// SPDX-License-Identifier: Apache-2.0
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
        // TODO: duplicated code from `OnDiskReadableSingletonState`
        final var key = getVirtualMapKey(serviceName, stateKey);
        var value = virtualMap.get(key, valueCodec);

        if (value == null && virtualMap.containsKey(key)) {
            value = valueCodec.getDefaultInstance();
        }

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
