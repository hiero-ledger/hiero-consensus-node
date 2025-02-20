// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.fixtures.util;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * In a JUnit5 test extended with the {@link LogCaptureExtension}, denotes the
 * field of a class type whose logs should be captured per test method execution.
 * <p/>
 * (The field itself isn't used - only its type is needed, to get a
 * {@link org.apache.logging.log4j.Logger} for that type - thus it need not ever
 * hold an instance.)
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface LoggingSubject {}
