// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.ClassOrderer;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.TestClassOrder;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.parallel.Isolated;

/**
 * Convenience annotation to mark a test class that requires strictly sequential execution,
 * both with respect to other test classes and within its own methods.
 */
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Isolated
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestClassOrder(ClassOrderer.OrderAnnotation.class)
public @interface OrderedInIsolation {}
