// SPDX-License-Identifier: Apache-2.0
package com.swirlds.base.test.fixtures.io;

import com.swirlds.base.test.fixtures.io.internal.SystemOutExtension;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Isolated;

/**
 * This annotation is used to inject a {@link SystemOutProvider} instance into a test and run the test in isolation.
 *
 * @see SystemOutProvider
 * @see WithSystemError
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Isolated
@Inherited
@ExtendWith(SystemOutExtension.class)
public @interface WithSystemOut {}
