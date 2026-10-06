// SPDX-License-Identifier: Apache-2.0
package org.hiero.sloth.fixtures;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.hiero.sloth.fixtures.junit.SlothTestExtension;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Annotation to mark a class as an sloth test.
 *
 * <p>This annotation can be used to specify that a method is a test case for the sloth framework.
 * An sloth test method can define one parameter of type {@link TestEnvironment} to access the test environment.
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@TestTemplate
@ExtendWith({SlothTestExtension.class})
public @interface Benchmark {

    /**
     * Specifies the capabilities required by the test. If an environment does not support all of the required capabilities, the test will be disabled.
     *
     * @return an array of required capabilities
     */
    Capability[] requires() default {};
}
