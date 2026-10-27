// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.extensions;

import static java.util.Objects.requireNonNull;

import com.hedera.services.bdd.junit.MultiNetworkHapiTest.Network;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * The set of shared networks a {@code @MultiNetworkHapiTest} method declares, e.g. {@code {ledgerA, ledgerB}}.
 * All tests declaring the same set of networks share one group: its networks boot once, stay warm across
 * those tests, and are torn down after the last one.
 *
 * @param id stable identity of the group: its distinct network names, sorted and comma-joined
 * @param networkNames the distinct network names in the group
 * @param weight the total number of nodes the group boots (the sum of its distinct networks' sizes)
 */
public record NetworkGroup(@NonNull String id, @NonNull Set<String> networkNames, int weight) {
    public NetworkGroup {
        requireNonNull(id);
        networkNames = Set.copyOf(networkNames);
    }

    /** The group a test's {@code @Network} annotations name. A repeated network name is counted once. */
    @NonNull
    public static NetworkGroup of(@NonNull final Network[] configs) {
        final Map<String, Integer> sizeByName = new TreeMap<>();
        for (final var cfg : configs) {
            sizeByName.putIfAbsent(MultiNetworkExtension.resolveName(cfg), cfg.size());
        }
        return of(sizeByName);
    }

    /** The group of the given networks, keyed by name with each network's node count. */
    @NonNull
    static NetworkGroup of(@NonNull final Map<String, Integer> sizeByName) {
        final SortedMap<String, Integer> sorted = new TreeMap<>(sizeByName);
        return new NetworkGroup(
                String.join(",", sorted.keySet()),
                sorted.keySet(),
                sorted.values().stream().mapToInt(Integer::intValue).sum());
    }
}
