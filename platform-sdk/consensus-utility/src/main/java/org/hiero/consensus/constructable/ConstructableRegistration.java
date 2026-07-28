// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.constructable;

import org.hiero.base.constructable.ClassConstructorPair;
import org.hiero.base.constructable.ConstructableRegistry;
import org.hiero.base.constructable.ConstructableRegistryException;
import org.hiero.base.crypto.Hash;
import org.hiero.base.crypto.SerializablePublicKey;
import org.hiero.base.io.SerializableLong;
import org.hiero.consensus.model.event.CesEvent;
import org.hiero.consensus.main.model.NodeId;

/**
 * Bundles the most commonly needed {@link ConstructableRegistry} registrations used across the application.
 */
public final class ConstructableRegistration {

    private ConstructableRegistration() {}

    /**
     * Add all classes to the constructable registry.
     */
    public static void setupConstructableRegistry() {
        try {
            registerAllConstructables();
        } catch (final ConstructableRegistryException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Registers {@link Hash}, {@link SerializablePublicKey}, {@link CesEvent}, and {@link NodeId}.
     */
    public static void registerCoreConstructables() throws ConstructableRegistryException {
        final ConstructableRegistry registry = ConstructableRegistry.getInstance();
        registry.registerConstructable(new ClassConstructorPair(Hash.class, Hash::new));
        registry.registerConstructable(
                new ClassConstructorPair(SerializablePublicKey.class, SerializablePublicKey::new));
        registry.registerConstructable(new ClassConstructorPair(CesEvent.class, CesEvent::new));
        registry.registerConstructable(new ClassConstructorPair(NodeId.class, NodeId::new));
    }

    /**
     * Registers {@link SerializableLong}.
     */
    public static void registerSyncConstructables() throws ConstructableRegistryException {
        final ConstructableRegistry registry = ConstructableRegistry.getInstance();
        registry.registerConstructable(new ClassConstructorPair(SerializableLong.class, SerializableLong::new));
    }

    /**
     * Registers all 9 common constructables (core + sync).
     */
    public static void registerAllConstructables() throws ConstructableRegistryException {
        registerCoreConstructables();
        registerSyncConstructables();
    }
}
