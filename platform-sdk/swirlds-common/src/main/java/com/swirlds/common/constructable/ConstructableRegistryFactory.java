// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.constructable;

import com.swirlds.common.constructable.internal.DefaultConstructableRegistry;
import com.swirlds.common.constructable.internal.GenericConstructorRegistry;

/**
 * Creates instances of {@link ConstructableRegistry} and {@link ConstructorRegistry}
 */
public final class ConstructableRegistryFactory {
    private ConstructableRegistryFactory() {}

    /**
     * @return a new instance of {@link ConstructableRegistry}
     */
    public static ConstructableRegistry createConstructableRegistry() {
        return new DefaultConstructableRegistry();
    }

    /**
     * @param constructorType
     * 		a class that represents the constructor type
     * @param <T>
     * 		the type of constructor used
     * @return a new instance of {@link ConstructorRegistry}
     */
    public static <T> ConstructorRegistry<T> createConstructorRegistry(final Class<T> constructorType) {
        return new GenericConstructorRegistry<>(constructorType);
    }
}
