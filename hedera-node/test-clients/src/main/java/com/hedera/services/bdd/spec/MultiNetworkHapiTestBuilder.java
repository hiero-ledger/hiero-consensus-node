// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec;

import com.hedera.services.bdd.junit.hedera.subprocess.SubProcessNetwork;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.DynamicTest;

/**
 * Minimal placeholder builder for multi-network HAPI tests. The full orchestration logic will be
 * integrated in a follow-up; for now this keeps the build healthy while allowing suites to compile.
 */
public final class MultiNetworkHapiTestBuilder {
    private static final Logger log = LogManager.getLogger(MultiNetworkHapiTestBuilder.class);

    private final List<SubProcessNetwork> networks;
    private final List<NetworkPlan> plans = new ArrayList<>();

    public MultiNetworkHapiTestBuilder(@NonNull final List<SubProcessNetwork> networks) {
        this.networks = List.copyOf(networks);
    }

    public MultiNetworkHapiTestBuilder onNetwork(
            @NonNull final String networkName, @NonNull final SpecOperation... operations) {
        plans.add(new NetworkPlan(networkName, List.of(operations)));
        return this;
    }

    public Stream<DynamicTest> asDynamicTests() {
        if (networks.isEmpty()) {
            return Stream.empty();
        }
        log.warn(
                "Multi-network test harness not yet implemented; skipping {} planned network segments across {} networks",
                plans.size(),
                networks.size());
        return Stream.empty();
    }

    private record NetworkPlan(String networkName, List<SpecOperation> operations) {}
}
