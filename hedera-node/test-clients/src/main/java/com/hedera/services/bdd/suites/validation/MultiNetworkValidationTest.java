// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.validation;

import static com.hedera.services.bdd.junit.TestTags.MULTINETWORK;
import static com.hedera.services.bdd.junit.hedera.subprocess.ProcessUtils.awaitStatus;
import static com.hedera.services.bdd.spec.HapiSpec.networkHapiTest;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateAllLogsAfter;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateStreams;
import static org.hiero.consensus.model.status.PlatformStatus.ACTIVE;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import com.hedera.services.bdd.junit.extensions.MultiNetworkExtension;
import com.hedera.services.bdd.junit.extensions.SpecNamingExtension;
import com.hedera.services.bdd.junit.hedera.subprocess.SubProcessNetwork;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.SpecOperation;
import java.time.Duration;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * Runs log validation and then stream validation against every shared multi-network network, after all
 * multi-network suites have run. Each network has its own working directory, which the validations resolve
 * from the network's nodes.
 *
 * <p>Ordered to come after every multi-network suite, since stream validation freezes each network.
 */
@Tag(MULTINETWORK)
@Order(Integer.MAX_VALUE)
public class MultiNetworkValidationTest {
    private static final Duration VALIDATION_DELAY = Duration.ofSeconds(1);
    private static final Duration ACTIVE_TIMEOUT = Duration.ofMinutes(5);

    @TestFactory
    @ExtendWith(SpecNamingExtension.class)
    @ResourceLock(value = "NETWORK")
    final Stream<DynamicTest> logsAndStreamsAreValid() {
        // Ensure we don't trigger any stake rebalancing that could interfere with validation
        HapiSpec.setStakerIds(null);
        final var networks = MultiNetworkExtension.SHARED_NETWORKS.values().stream()
                .sorted(Comparator.comparing(SubProcessNetwork::name))
                .toList();
        // Validate every network's logs before freezing any of them; a frozen network makes its peers log
        // sync failures, which would otherwise fail the log validation of networks validated after it
        return Stream.concat(
                networks.stream().map(network -> validationOf(network, "logs", validateAllLogsAfter(VALIDATION_DELAY))),
                networks.stream().map(network -> validationOf(network, "streams", validateStreams())));
    }

    private static DynamicTest validationOf(
            final SubProcessNetwork network, final String description, final SpecOperation validation) {
        final var test =
                networkHapiTest(description, network, validation).findFirst().orElseThrow();
        return dynamicTest(test.getDisplayName(), () -> {
            // An earlier suite may have left the network restarting
            network.nodes().forEach(node -> awaitStatus(node, ACTIVE_TIMEOUT, ACTIVE));
            // Validators build their own specs (e.g. to snapshot balances), which target this thread's network
            HapiSpec.TARGET_NETWORK.set(network);
            try {
                test.getExecutable().execute();
            } finally {
                HapiSpec.TARGET_NETWORK.remove();
            }
        });
    }
}
