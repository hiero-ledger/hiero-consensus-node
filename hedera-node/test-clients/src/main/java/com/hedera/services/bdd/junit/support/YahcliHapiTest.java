// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support;

import static org.junit.jupiter.api.parallel.ResourceAccessMode.READ;

import com.hedera.services.bdd.junit.extensions.NetworkTargetingExtension;
import com.hedera.services.bdd.junit.extensions.SpecNamingExtension;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * The main annotation in this package; marks a method as a factory for dynamic tests
 * that will target the shared test network and use the {@link SpecNamingExtension} to
 * name the tests.
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@TestFactory
@ExtendWith({NetworkTargetingExtension.class, SpecNamingExtension.class})
@ResourceLock(value = "NETWORK", mode = READ)
public @interface YahcliHapiTest {}
