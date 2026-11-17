// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit;

import static com.hedera.services.bdd.junit.TestTags.SERIAL;
import static org.junit.jupiter.api.parallel.ResourceAccessMode.READ_WRITE;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.ClassOrderer;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestClassOrder;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * Convenience annotation to mark a test class that requires strictly sequential execution,
 * both with respect to other test classes and within its own methods.
 * Tests annotated with this will run in subprocess sequential mode rather than concurrent PR checks.
 */
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@ResourceLock(value = "NETWORK", mode = READ_WRITE)
@Tag(SERIAL)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestClassOrder(ClassOrderer.OrderAnnotation.class)
public @interface OrderedInIsolation {}
