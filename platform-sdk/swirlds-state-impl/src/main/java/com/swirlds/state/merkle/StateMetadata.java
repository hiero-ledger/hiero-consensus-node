// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle;

import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Holds metadata related to a registered service's state.
 *
 * @param <K> The type of the state key
 * @param <V> The type of the state value
 */
public final class StateMetadata<K, V> {
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

    private final String serviceName;
    private final Schema schema;
    private final StateDefinition<K, V> stateDefinition;
    private final long onDiskKeyClassId;
    private final long onDiskKeySerializerClassId;
    private final long onDiskValueClassId;
    private final long onDiskValueSerializerClassId;
    private final long inMemoryValueClassId;
    private final long singletonClassId;
    private final long queueNodeClassId;

    /**
     * Create an instance.
     *
     * @param serviceName The name of the service
     * @param schema The {@link Schema} that defined the state
     * @param stateDefinition The {@link StateDefinition}
     */
    public StateMetadata(
            @NonNull String serviceName, @NonNull Schema schema, @NonNull StateDefinition<K, V> stateDefinition) {
        this.serviceName = StateUtils.validateServiceName(serviceName);
        this.schema = schema;
        this.stateDefinition = stateDefinition;

        final var stateKey = stateDefinition.stateKey();
        final var version = schema.getVersion();
        this.onDiskKeyClassId = StateUtils.computeClassId(serviceName, stateKey, version, ON_DISK_KEY_CLASS_ID_SUFFIX);
        this.onDiskKeySerializerClassId =
                StateUtils.computeClassId(serviceName, stateKey, version, ON_DISK_KEY_SERIALIZER_CLASS_ID_SUFFIX);
        this.onDiskValueClassId =
                StateUtils.computeClassId(serviceName, stateKey, version, ON_DISK_VALUE_CLASS_ID_SUFFIX);
        this.onDiskValueSerializerClassId =
                StateUtils.computeClassId(serviceName, stateKey, version, ON_DISK_VALUE_SERIALIZER_CLASS_ID_SUFFIX);
        this.inMemoryValueClassId =
                StateUtils.computeClassId(serviceName, stateKey, version, IN_MEMORY_VALUE_CLASS_ID_SUFFIX);
        this.singletonClassId = StateUtils.computeClassId(serviceName, stateKey, version, SINGLETON_CLASS_ID_SUFFIX);
        this.queueNodeClassId = StateUtils.computeClassId(serviceName, stateKey, version, QUEUE_NODE_CLASS_ID_SUFFIX);
    }

    public String serviceName() {
        return serviceName;
    }

    public Schema schema() {
        return schema;
    }

    public @NonNull StateDefinition<K, V> stateDefinition() {
        return stateDefinition;
    }

    public long onDiskKeyClassId() {
        return onDiskKeyClassId;
    }

    public long onDiskKeySerializerClassId() {
        return onDiskKeySerializerClassId;
    }

    public long onDiskValueClassId() {
        return onDiskValueClassId;
    }

    public long onDiskValueSerializerClassId() {
        return onDiskValueSerializerClassId;
    }

    public long inMemoryValueClassId() {
        return inMemoryValueClassId;
    }

    public long singletonClassId() {
        return singletonClassId;
    }

    public long queueNodeClassId() {
        return queueNodeClassId;
    }
}
