// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.fixtures.util;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * In a JUnit5 test extended with the {@link LogCaptureExtension}, denotes the field to be injected
 * with the scoped {@link LogCaptor} instance.
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface LoggingTarget {}
