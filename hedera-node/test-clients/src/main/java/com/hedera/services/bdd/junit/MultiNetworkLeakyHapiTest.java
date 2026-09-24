// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@link MultiNetworkHapiTest} that mutates shared network state and so must run with EXCLUSIVE
 * ({@code READ_WRITE}) access to its networks, rather than the concurrent-reader default.
 *
 * <p>Apply it to any test that installs a different ledger throttle / config, overrides a network
 * property, or restarts a network. Absent, a test is treated as a reader (it only exercises its own
 * channel) and may run concurrently with other readers on the same networks.
 *
 * <p>Read at runtime by {@link com.hedera.services.bdd.junit.extensions.ClprNetworkLocksProvider}. May be
 * placed on a method, or on a class to mark every {@code @MultiNetworkHapiTest} method in it leaky.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface MultiNetworkLeakyHapiTest {}
