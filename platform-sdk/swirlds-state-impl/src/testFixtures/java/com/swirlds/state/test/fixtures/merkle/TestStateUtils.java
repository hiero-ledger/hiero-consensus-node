// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.test.fixtures.merkle;

import com.swirlds.state.lifecycle.StateMetadata;
import com.swirlds.state.test.fixtures.merkle.disk.OnDiskKey;
import com.swirlds.state.test.fixtures.merkle.disk.OnDiskKeySerializer;
import com.swirlds.state.test.fixtures.merkle.disk.OnDiskValue;
import com.swirlds.state.test.fixtures.merkle.disk.OnDiskValueSerializer;
import com.swirlds.state.test.fixtures.merkle.memory.InMemoryValue;
import com.swirlds.state.test.fixtures.merkle.queue.QueueNode;
import com.swirlds.state.test.fixtures.merkle.singleton.SingletonNode;
import com.swirlds.state.test.fixtures.merkle.singleton.StringLeaf;
import com.swirlds.state.test.fixtures.merkle.singleton.ValueLeaf;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.base.constructable.ClassConstructorPair;
import org.hiero.base.constructable.ConstructableRegistry;
import org.hiero.base.constructable.ConstructableRegistryException;

public class TestStateUtils {

    /**
     * Registers with the {@link ConstructableRegistry} system a class ID and a class. While this
     * will only be used for in-memory states, it is safe to register for on-disk ones as well.
     *
     * <p>The implementation will take the service name and the state key and compute a hash for it.
     *
     *
     * @deprecated Registrations should be removed when there are no longer any objects of the relevant class.
     * Once all registrations have been removed, this method itself should be deleted.
     * See <a href="https://github.com/hiero-ledger/hiero-consensus-node/issues/19416">GitHub issue</a>.
     *
     * @param md The state metadata
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    @Deprecated
    public static void registerWithSystem(
            @NonNull final StateMetadata md, @NonNull final ConstructableRegistry constructableRegistry) {
        // Register with the system the uniqueId as the "classId" of an InMemoryValue. There can be
        // multiple id's associated with InMemoryValue. The secret is that the supplier captures the
        // various delegate writers and parsers, and so can parse/write different types of data
        // based on the id.
        try {
            constructableRegistry.registerConstructable(new ClassConstructorPair(
                    InMemoryValue.class,
                    () -> new InMemoryValue(
                            md.inMemoryValueClassId(),
                            md.stateDefinition().keyCodec(),
                            md.stateDefinition().valueCodec())));
            // FUTURE WORK: remove OnDiskKey registration, once there are no objects of this class
            // in existing state snapshots
            constructableRegistry.registerConstructable(new ClassConstructorPair(
                    OnDiskKey.class,
                    () -> new OnDiskKey<>(
                            md.onDiskKeyClassId(), md.stateDefinition().keyCodec())));
            // FUTURE WORK: remove OnDiskKeySerializer registration, once there are no objects of this class
            // in existing state snapshots
            constructableRegistry.registerConstructable(new ClassConstructorPair(
                    OnDiskKeySerializer.class,
                    () -> new OnDiskKeySerializer<>(
                            md.onDiskKeySerializerClassId(),
                            md.onDiskKeyClassId(),
                            md.stateDefinition().keyCodec())));
            // FUTURE WORK: remove OnDiskValue registration, once there are no objects of this class
            // in existing state snapshots
            constructableRegistry.registerConstructable(new ClassConstructorPair(
                    OnDiskValue.class,
                    () -> new OnDiskValue<>(
                            md.onDiskValueClassId(), md.stateDefinition().valueCodec())));
            // FUTURE WORK: remove OnDiskValueSerializer registration, once there are no objects of this class
            // in existing state snapshots
            constructableRegistry.registerConstructable(new ClassConstructorPair(
                    OnDiskValueSerializer.class,
                    () -> new OnDiskValueSerializer<>(
                            md.onDiskValueSerializerClassId(),
                            md.onDiskValueClassId(),
                            md.stateDefinition().valueCodec())));
            constructableRegistry.registerConstructable(new ClassConstructorPair(
                    SingletonNode.class,
                    () -> new SingletonNode<>(
                            md.serviceName(),
                            md.singletonClassId(),
                            md.stateDefinition().valueCodec(),
                            null)));
            constructableRegistry.registerConstructable(new ClassConstructorPair(
                    QueueNode.class,
                    () -> new QueueNode<>(
                            md.serviceName(),
                            md.queueNodeClassId(),
                            md.singletonClassId(),
                            md.stateDefinition().valueCodec())));
            constructableRegistry.registerConstructable(new ClassConstructorPair(StringLeaf.class, StringLeaf::new));
            constructableRegistry.registerConstructable(new ClassConstructorPair(
                    ValueLeaf.class,
                    () -> new ValueLeaf<>(
                            md.singletonClassId(), md.stateDefinition().valueCodec())));
        } catch (ConstructableRegistryException e) {
            // This is a fatal error.
            throw new IllegalStateException(
                    "Failed to register with the system '"
                            + md.serviceName()
                            + ":"
                            + md.stateDefinition().stateKey()
                            + "'",
                    e);
        }
    }
}
