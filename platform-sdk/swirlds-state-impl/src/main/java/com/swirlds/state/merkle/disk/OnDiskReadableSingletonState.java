// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle.disk;

import static com.swirlds.state.merkle.StateUtils.computeLabel;
import static com.swirlds.state.merkle.StateUtils.getVirtualMapKey;
import static com.swirlds.state.merkle.logging.StateLogger.logSingletonRead;
import static java.util.Objects.requireNonNull;

import com.hedera.pbj.runtime.Codec;
import com.swirlds.state.spi.ReadableSingletonStateBase;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;

public class OnDiskReadableSingletonState<T> extends ReadableSingletonStateBase<T> {

    @NonNull
    private final VirtualMap virtualMap;

    @NonNull
    private final Codec<T> valueCodec;

    public OnDiskReadableSingletonState(
            @NonNull final String serviceName,
            @NonNull final String stateKey,
            @NonNull final Codec<T> valueCodec,
            @NonNull final VirtualMap virtualMap) {
        super(serviceName, stateKey);

        this.virtualMap = requireNonNull(virtualMap);
        this.valueCodec = requireNonNull(valueCodec);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected T readFromDataSource() {
        final var key = getVirtualMapKey(serviceName, stateKey);
        var value = virtualMap.get(key, valueCodec);

        if (value == null && virtualMap.containsKey(key)) {
            value = valueCodec.getDefaultInstance();
        }

        // Log to transaction state log, what was read
        logSingletonRead(computeLabel(serviceName, stateKey), value);
        return value;
    }
}
