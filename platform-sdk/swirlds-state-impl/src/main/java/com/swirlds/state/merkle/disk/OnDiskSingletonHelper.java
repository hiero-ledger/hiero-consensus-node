// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle.disk;

import com.hedera.pbj.runtime.Codec;
import com.swirlds.state.merkle.StateUtils;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A helper class for managing an on-disk singleton using a {@link VirtualMap} as the core storage mechanism.
 *
 * <p>This class was created to extract repetitive code from
 * {@code OnDiskWritableQueueState} and {@code OnDiskReadableQueueState}.
 *
 * @param <T> the type of element stored in the on-disk singleton
 */
public final class OnDiskSingletonHelper<T> {

    private OnDiskSingletonHelper() {}

    /**
     * Retrieves a singleton object from the backing data store using the provided parameters.
     *
     * @param serviceName the name of the service that owns the singleton's state
     * @param stateKey    the unique key for identifying the singleton's state
     * @param virtualMap  the storage mechanism for the singleton's data
     * @param valueCodec  the codec for the elements of the singleton
     * @param <T>         the type of element stored in the on-disk singleton
     * @return the singleton object
     */
    public static <T> T getFromStore(
            @NonNull final String serviceName,
            @NonNull final String stateKey,
            @NonNull final VirtualMap virtualMap,
            @NonNull final Codec<T> valueCodec) {
        final var key = StateUtils.getVirtualMapKeyForSingleton(serviceName, stateKey);
        var value = virtualMap.get(key, valueCodec);

        if (value == null && virtualMap.containsKey(key)) {
            value = valueCodec.getDefaultInstance();
        }

        return value;
    }
}
