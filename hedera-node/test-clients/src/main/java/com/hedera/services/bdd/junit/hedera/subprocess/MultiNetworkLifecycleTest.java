// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.hedera.subprocess;

import com.hedera.services.bdd.suites.regression.system.LifecycleTest;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-network config (software) version tracking for multi-network tests.
 *
 * <p>Single-network HAPI tests share the one process-wide {@link LifecycleTest#CURRENT_CONFIG_VERSION}
 * counter, which is correct when there is exactly one network in the JVM. Multi-network tests run
 * several independent subprocess networks concurrently, so a single global counter lets one network's
 * config-version upgrade (software {@code build} bump) leak into another network's node start, producing
 * a state/software version mismatch ({@code Cannot downgrade from build=N to build=M}) that crashes the
 * node on restart.
 *
 * <p>This registry gives each multi-network network its own counter, keyed by network name. Only
 * networks explicitly {@link #register(String) registered} (by {@code MultiNetworkExtension} when it
 * creates each network) use a per-network counter; every other network (all single-network tests,
 * whatever their name) falls back to {@link LifecycleTest#CURRENT_CONFIG_VERSION}, so single-network
 * behavior is unchanged.
 */
public interface MultiNetworkLifecycleTest {
    /** Per-network config-version counters, keyed by network name. */
    Map<String, AtomicInteger> CONFIG_VERSION_BY_NETWORK = new ConcurrentHashMap<>();

    /**
     * Registers a per-network config-version counter (starting at 0) for the given network, if not
     * already present. Called when the network is created, so its genesis start reads its own version
     * rather than the shared global.
     *
     * @param network the network name
     */
    static void register(final String network) {
        CONFIG_VERSION_BY_NETWORK.putIfAbsent(network, new AtomicInteger(0));
    }

    /**
     * Returns the current config version for the given network: its own counter if registered, else the
     * shared {@link LifecycleTest#CURRENT_CONFIG_VERSION} (single-network fallback).
     *
     * @param network the network name
     * @return the current config version
     */
    static int configVersionOf(final String network) {
        final var perNetwork = CONFIG_VERSION_BY_NETWORK.get(network);
        return perNetwork != null ? perNetwork.get() : LifecycleTest.CURRENT_CONFIG_VERSION.get();
    }

    /**
     * Increments and returns the config version for the given network: its own counter if registered,
     * else the shared {@link LifecycleTest#CURRENT_CONFIG_VERSION} (single-network fallback).
     *
     * @param network the network name
     * @return the incremented config version
     */
    static int nextConfigVersionOf(final String network) {
        final var perNetwork = CONFIG_VERSION_BY_NETWORK.get(network);
        return perNetwork != null
                ? perNetwork.incrementAndGet()
                : LifecycleTest.CURRENT_CONFIG_VERSION.incrementAndGet();
    }
}
