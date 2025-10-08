// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to specify optional configuration parameters which applies to all environments.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface OtterSpecs {

    /**
     * Determines whether the node IDs should be selected randomly.
     *
     * @return {@code true} if the node IDs are selected randomly; {@code false} otherwise
     */
    boolean randomNodeIds() default true;
}
