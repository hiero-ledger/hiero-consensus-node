// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle.disk;

import static com.swirlds.state.merkle.StateUtils.getVirtualMapKeyForSingleton;

import com.hedera.hapi.platform.state.VirtualMapValue;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A helper class for managing an on-disk singleton using a {@link VirtualMap} as the core storage mechanism.
 *
 * <p>This class was created to extract repetitive code from
 * {@code OnDiskWritableQueueState} and {@code OnDiskReadableQueueState}.
 */
public final class OnDiskSingletonHelper {

    private OnDiskSingletonHelper() {}

    /**
     * Retrieves a singleton object from the backing data store using the provided parameters.
     *
     * @param serviceName the name of the service that owns the singleton's state
     * @param stateKey    the unique key for identifying the singleton's state
     * @param virtualMap  the storage mechanism for the singleton's data
     * @param <T>         the type of element stored in the on-disk singleton
     * @return the singleton object
     */
    public static <T> T getFromStore(
            @NonNull final String serviceName, @NonNull final String stateKey, @NonNull final VirtualMap virtualMap) {
        final Bytes key = getVirtualMapKeyForSingleton(serviceName, stateKey);
        final VirtualMapValue virtualMapValue = virtualMap.get(key, VirtualMapValue.PROTOBUF);
        return virtualMapValue != null ? virtualMapValue.value().as() : null;
    }
}
