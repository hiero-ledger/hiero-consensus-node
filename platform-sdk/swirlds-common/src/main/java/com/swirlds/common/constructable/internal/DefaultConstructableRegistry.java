// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.constructable.internal;

import com.swirlds.common.constructable.ClassConstructorPair;
import com.swirlds.common.constructable.ConstructableRegistry;
import com.swirlds.common.constructable.ConstructableRegistryException;
import com.swirlds.common.constructable.ConstructorRegistry;
import com.swirlds.common.constructable.NoArgsConstructor;
import com.swirlds.common.constructable.URLClassLoaderWithLookup;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.hiero.consensus.model.constructable.RuntimeConstructable;

public class DefaultConstructableRegistry implements ConstructableRegistry {
    private final Map<Class<?>, GenericConstructorRegistry<?>> allRegistries = new ConcurrentHashMap<>();

    @Override
    @SuppressWarnings("unchecked")
    public <T> ConstructorRegistry<T> getRegistry(final Class<T> constructorType) {
        return (ConstructorRegistry<T>) allRegistries.get(constructorType);
    }

    @Override
    public Supplier<RuntimeConstructable> getConstructor(final long classId) {
        return getOrCreate(NoArgsConstructor.class).getConstructor(classId);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T extends RuntimeConstructable> T createObject(final long classId) {
        final Supplier<RuntimeConstructable> c = getConstructor(classId);
        if (c == null) {
            return null;
        }
        return (T) c.get();
    }

    @Override
    public void registerConstructables(final String packagePrefix, final URLClassLoaderWithLookup additionalClassloader)
            throws ConstructableRegistryException {
        final Collection<ConstructableClasses<?>> scanResults =
                ConstructableScanner.getConstructableClasses(packagePrefix, additionalClassloader);
        for (final ConstructableClasses<?> constructableClasses : scanResults) {
            getOrCreate(constructableClasses.getConstructorType())
                    .registerConstructables(constructableClasses, additionalClassloader);
        }
    }

    @Override
    public void registerConstructables(final String packagePrefix) throws ConstructableRegistryException {
        registerConstructables(packagePrefix, null);
    }

    @Override
    public void registerConstructable(final ClassConstructorPair pair) throws ConstructableRegistryException {
        getOrCreate(NoArgsConstructor.class)
                .registerConstructable(pair.getConstructableClass(), pair.getConstructor()::get);
    }

    @Override
    public void reset() {
        allRegistries.clear();
    }

    @SuppressWarnings("unchecked")
    private <T> GenericConstructorRegistry<T> getOrCreate(final Class<T> constructor) {
        return (GenericConstructorRegistry<T>)
                allRegistries.computeIfAbsent(constructor, GenericConstructorRegistry::new);
    }
}
