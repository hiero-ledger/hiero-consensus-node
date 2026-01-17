// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.test.fixtures.merkle;

import com.hedera.hapi.node.base.SemanticVersion;
import com.swirlds.state.lifecycle.StateMetadata;
import edu.umd.cs.findbugs.annotations.NonNull;

public class StateClassIdUtils {
    // The application framework reuses the same merkle nodes for different types of encoded data.
    // When written to saved state, the type of data is determined with a "class ID", which is just
    // a long. When a saved state is deserialized, the platform will read the "class ID" and then
    // lookup in ConstructableRegistry the associated class to use for parsing the data.
    //
    // We generate class IDs dynamically based on the StateMetadata. The algorithm used for generating
    // this class ID cannot change in the future, otherwise state already in the saved state file
    // will not be retrievable!
    private static final String ON_DISK_KEY_CLASS_ID_SUFFIX = "OnDiskKey";
    private static final String ON_DISK_KEY_SERIALIZER_CLASS_ID_SUFFIX = "OnDiskKeySerializer";
    private static final String ON_DISK_VALUE_CLASS_ID_SUFFIX = "OnDiskValue";
    private static final String ON_DISK_VALUE_SERIALIZER_CLASS_ID_SUFFIX = "OnDiskValueSerializer";
    private static final String IN_MEMORY_VALUE_CLASS_ID_SUFFIX = "InMemoryValue";
    private static final String SINGLETON_CLASS_ID_SUFFIX = "SingletonLeaf";
    private static final String QUEUE_NODE_CLASS_ID_SUFFIX = "QueueNode";

    public static long onDiskKeyClassId(String serviceName, String stateKey, SemanticVersion version) {
        return computeClassId(serviceName, stateKey, version, ON_DISK_KEY_CLASS_ID_SUFFIX);
    }

    public static long onDiskKeySerializerClassId(String serviceName, String stateKey, SemanticVersion version) {
        return computeClassId(serviceName, stateKey, version, ON_DISK_KEY_SERIALIZER_CLASS_ID_SUFFIX);
    }

    public static long onDiskValueClassId(String serviceName, String stateKey, SemanticVersion version) {
        return computeClassId(serviceName, stateKey, version, ON_DISK_VALUE_CLASS_ID_SUFFIX);
    }

    public static long onDiskValueSerializerClassId(String serviceName, String stateKey, SemanticVersion version) {
        return computeClassId(serviceName, stateKey, version, ON_DISK_VALUE_SERIALIZER_CLASS_ID_SUFFIX);
    }

    public static long inMemoryValueClassId(String serviceName, String stateKey, SemanticVersion version) {
        return computeClassId(serviceName, stateKey, version, IN_MEMORY_VALUE_CLASS_ID_SUFFIX);
    }

    public static long singletonClassId(String serviceName, String stateKey, SemanticVersion version) {
        return computeClassId(serviceName, stateKey, version, SINGLETON_CLASS_ID_SUFFIX);
    }

    public static long queueNodeClassId(String serviceName, String stateKey, SemanticVersion version) {
        return computeClassId(serviceName, stateKey, version, QUEUE_NODE_CLASS_ID_SUFFIX);
    }

    /**
     * Given the inputs, compute the corresponding class ID.
     *
     * @param extra An extra string to bake into the class id
     * @return the class id
     */
    public static long computeClassId(
            @NonNull final String serviceName,
            @NonNull final String stateKey,
            @NonNull final SemanticVersion version,
            @NonNull final String extra) {
        // NOTE: Once this is live on any network, the formula used to generate this key can NEVER
        // BE CHANGED or you won't ever be able to deserialize an exising state! If we get away from
        // this formula, we will need to hardcode known classId that had been previously generated.
        final var ver = "v" + version.major() + "." + version.minor() + "." + version.patch();
        return StateMetadata.hashString(serviceName + ":" + stateKey + ":" + ver + ":" + extra);
    }
}
