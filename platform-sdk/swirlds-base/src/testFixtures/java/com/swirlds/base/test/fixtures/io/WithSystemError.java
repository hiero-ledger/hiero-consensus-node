// SPDX-License-Identifier: Apache-2.0
package com.swirlds.base.test.fixtures.io;

import com.swirlds.base.test.fixtures.io.internal.SystemErrorExtension;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Isolated;

/**
 * This annotation is used to inject a {@link SystemErrProvider} instance into a test and run the test in isolation.
 *
 * @see SystemErrProvider
 * @see WithSystemOut
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Isolated
@Inherited
@ExtendWith(SystemErrorExtension.class)
public @interface WithSystemError {}
