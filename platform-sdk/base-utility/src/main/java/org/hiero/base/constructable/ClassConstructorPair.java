// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.constructable;

import java.util.function.Supplier;

/**
 * @deprecated see {@link ConstructableRegistry#registerConstructable(ClassConstructorPair)}
 */
@Deprecated(forRemoval = true)
public class ClassConstructorPair {
    private final Class<? extends RuntimeConstructable> aClass;
    private final Supplier<? extends RuntimeConstructable> constructor;

    public ClassConstructorPair(
            final Class<? extends RuntimeConstructable> aClass,
            final Supplier<? extends RuntimeConstructable> constructor) {
        this.aClass = aClass;
        this.constructor = constructor;
    }

    public Class<? extends RuntimeConstructable> getConstructableClass() {
        return aClass;
    }

    public Supplier<? extends RuntimeConstructable> getConstructor() {
        return constructor;
    }

    public boolean classEquals(ClassConstructorPair pair) {
        return this.aClass.equals(pair.getConstructableClass());
    }
}
