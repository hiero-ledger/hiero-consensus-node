// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.hiero.otter.fixtures.junit.OtterTestExtension;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Annotation to mark a class as an Otter test.
 *
 * <p>This annotation can be used to specify that a method is a test case for the Otter framework.
 * An Otter test method can define one parameter of type {@link TestEnvironment} to access the test environment.
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@TestTemplate
@ExtendWith({OtterTestExtension.class})
public @interface OtterTest {}
