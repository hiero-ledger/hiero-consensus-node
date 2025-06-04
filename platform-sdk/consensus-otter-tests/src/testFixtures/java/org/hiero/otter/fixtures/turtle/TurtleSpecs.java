// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.turtle;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.hiero.otter.fixtures.TestEnvironment;

/**
 * Annotation to mark a class as an Otter test.
 *
 * <p>This annotation can be used to specify that a method is a test case for the Otter framework.
 * An Otter test method can define one parameter of type {@link TestEnvironment} to access the test environment.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface TurtleSpecs {

    /**
     * The name of the test.
     *
     * @return the name of the test
     */
    long randomSeed() default 0L;
}
