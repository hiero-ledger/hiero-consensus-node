// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.test.fixtures;

import com.swirlds.logging.test.fixtures.internal.LoggerMirrorExtension;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Isolated;

/**
 * A JUnit 5 annotation that can be used to inject a {@link LoggingMirror} into a test method or test class. Tests that
 * are annotated with this annotation will never be executed in parallel. By doing so each test will have its isolated
 * access to a {@link LoggingMirror} instance that can be used to check the logging events that were generated during a
 * test.
 *
 * @see LoggingMirror
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Isolated
@ExtendWith(LoggerMirrorExtension.class)
public @interface WithLoggingMirror {}
