// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.constructable.internal;

import org.hiero.consensus.model.constructable.RuntimeConstructable;

/**
 * A {@link RuntimeConstructable} class with its constructor
 *
 * @param constructable
 * 		a {@link RuntimeConstructable} class
 * @param constructor
 * 		its constructor
 * @param <T>
 * 		the type of constructor
 */
public record GenericClassConstructorPair<T>(Class<? extends RuntimeConstructable> constructable, T constructor) {

    /**
     * Is this constructable class equal to the one from the supplied pair
     *
     * @param pair
     * 		the pair to compare to
     * @return true if the classes are the same
     */
    public boolean classEquals(final GenericClassConstructorPair<?> pair) {
        return this.constructable.equals(pair.constructable());
    }
}
