// SPDX-License-Identifier: Apache-2.0
package com.swirlds.base.test.fixtures.concurrent;

import com.swirlds.base.test.fixtures.concurrent.internal.TestExecutorExtension;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * This annotation is used to inject a {@link TestExecutor} instance into a test.
 *
 * @see TestExecutor
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@ExtendWith(TestExecutorExtension.class)
public @interface WithTestExecutor {}
