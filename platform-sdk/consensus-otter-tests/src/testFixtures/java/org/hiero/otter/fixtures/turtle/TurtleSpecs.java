// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.turtle;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to specify optional configuration parameters that are specific to the Turtle environment.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface TurtleSpecs {

    /**
     * Fixed seed for the PRNG of the test. If set to {@code 0} (the default), a random seed will be generated.
     *
     * @return the random seed
     */
    long randomSeed() default 0L;
}
