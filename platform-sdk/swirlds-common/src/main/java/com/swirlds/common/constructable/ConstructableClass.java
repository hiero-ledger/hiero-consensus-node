// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.constructable;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.hiero.consensus.model.constructable.RuntimeConstructable;

/**
 * Used to annotate constructable classes. Currently, this is needed if a custom constructor type is needed instead of
 * the default {@link NoArgsConstructor}. In the future, this will replace the interface {@link RuntimeConstructable}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ConstructableClass {
    /**
     * The class ID for this constructable class. Should be the same value as {@link RuntimeConstructable#getClassId()}
     *
     * @return the numeric ID of this class
     */
    long value();

    /**
     * Defines what kind of constructor a {@link RuntimeConstructable} class will use. The class provided should be a
     * {@link FunctionalInterface}, meaning it should only have a single method. The return type of this method should
     * extend {@link RuntimeConstructable}. A class that uses this annotation should have a constructor that have the
     * same signature as the interface provided.
     *
     * @return an interface representing the type of constructor used
     */
    Class<?> constructorType() default NoArgsConstructor.class;
}
