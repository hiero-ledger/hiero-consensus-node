// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.extensions;

import com.hedera.services.bdd.junit.MultiNetworkHapiTest;
import com.hedera.services.bdd.junit.MultiNetworkLeakyHapiTest;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLocksProvider;
import org.junit.platform.commons.support.AnnotationSupport;

/**
 * Derives a multi-network test's resource locks at runtime from its {@code @Network} set, so JUnit
 * serializes same-network tests while different network groups run in parallel.
 *
 * <p>Each network the test declares becomes one {@link Lock} keyed by network name. The access mode is
 * {@code READ} by default, so tests that only exercise their own channel run concurrently as readers, or
 * {@code READ_WRITE} when the method (or its class) is {@link MultiNetworkLeakyHapiTest} because it mutates shared state (a
 * different ledger throttle / config, a network property override, or a restart), so it runs alone.
 *
 * <p>Wired in via {@code @ResourceLock(providers = ClprNetworkLocksProvider.class)} on
 * {@link MultiNetworkHapiTest}, so JUnit calls {@link #provideForMethod} for every multi-network test.
 */
public final class ClprNetworkLocksProvider implements ResourceLocksProvider {

    @Override
    public Set<Lock> provideForMethod(
            final List<Class<?>> enclosingInstanceTypes, final Class<?> testClass, final Method testMethod) {
        final var annotation = AnnotationSupport.findAnnotation(testMethod, MultiNetworkHapiTest.class)
                .orElse(null);
        if (annotation == null) {
            return Set.of();
        }
        final var mode = isLeaky(testClass, testMethod) ? ResourceAccessMode.READ_WRITE : ResourceAccessMode.READ;
        final Set<Lock> locks = new HashSet<>();
        for (final var network : annotation.value()) {
            locks.add(new Lock(MultiNetworkExtension.resolveName(network), mode));
        }
        return locks;
    }

    private static boolean isLeaky(final Class<?> testClass, final Method testMethod) {
        return AnnotationSupport.isAnnotated(testMethod, MultiNetworkLeakyHapiTest.class)
                || AnnotationSupport.isAnnotated(testClass, MultiNetworkLeakyHapiTest.class);
    }
}
