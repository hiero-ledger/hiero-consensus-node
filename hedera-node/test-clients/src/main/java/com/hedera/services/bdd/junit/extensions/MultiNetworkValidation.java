// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.extensions;

import static com.hedera.services.bdd.junit.hedera.subprocess.ProcessUtils.awaitStatus;
import static com.hedera.services.bdd.spec.HapiSpec.networkHapiTest;
import static org.hiero.consensus.model.status.PlatformStatus.ACTIVE;
import static org.junit.jupiter.api.DynamicContainer.dynamicContainer;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import com.hedera.services.bdd.junit.hedera.subprocess.SubProcessNetwork;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.SpecOperation;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicTest;
import org.opentest4j.TestAbortedException;

/**
 * Validation of network groups as part of their lifecycle.
 *
 * <p>When {@code MultiNetworkValidationTest} is in the test plan (and classes run concurrently), every network group
 * awaits validation after its tests, in {@link GroupPhase#AWAITING_VALIDATION}. The validation test takes the groups
 * as they get there and validates each in one {@link DynamicContainer}; closing the container moves the group on to
 * teardown.
 */
public final class MultiNetworkValidation {
    /**
     * A group taken for validation.
     *
     * @param group the group
     * @param notValidatedReason why its networks cannot be validated (e.g. they failed to start), or null
     */
    public record ReadyGroup(
            @NonNull NetworkGroup group, @Nullable String notValidatedReason) {}

    /** How long to wait for a network an earlier test left restarting to be ACTIVE again. */
    private static final Duration ACTIVE_TIMEOUT = Duration.ofMinutes(5);

    private MultiNetworkValidation() {}

    /**
     * Returns one container per network group, as the groups get to validation, each validating the logs of every
     * network of the group and then their streams: stream validation freezes a network, which makes its peers log
     * sync failures. Closing a container moves the group on to teardown, so the factory returning this must run its
     * dynamic tests in its own thread ({@code @Execution(SAME_THREAD)}), so that a container closes only after its
     * tests have run.
     *
     * @param logValidation supplies the log validation to run against each network
     * @param streamValidation supplies the stream validation to run against each network
     * @return the containers
     */
    public static Stream<DynamicContainer> groupsToValidate(
            @NonNull final Supplier<SpecOperation> logValidation,
            @NonNull final Supplier<SpecOperation> streamValidation) {
        final var queue = MultiNetworkExtension.NETWORK_GROUP_QUEUE;
        return Stream.generate(queue::takeGroupToValidate)
                .takeWhile(Objects::nonNull)
                .map(target -> dynamicContainer(
                        target.group().id(),
                        testsFor(target, logValidation, streamValidation)
                                .onClose(() -> MultiNetworkExtension.finishValidation(target.group()))));
    }

    private static Stream<DynamicTest> testsFor(
            @NonNull final ReadyGroup target,
            @NonNull final Supplier<SpecOperation> logValidation,
            @NonNull final Supplier<SpecOperation> streamValidation) {
        if (target.notValidatedReason() != null) {
            return Stream.of(dynamicTest("not validated", () -> {
                throw new TestAbortedException(target.notValidatedReason());
            }));
        }
        final var names = target.group().networkNames().stream().sorted().toList();
        return Stream.concat(
                names.stream().map(name -> dynamicTest(name + " logs", () -> validate(name, logValidation.get()))),
                names.stream()
                        .map(name -> dynamicTest(name + " streams", () -> validate(name, streamValidation.get()))));
    }

    private static void validate(@NonNull final String name, @NonNull final SpecOperation validation) throws Throwable {
        final SubProcessNetwork network = MultiNetworkExtension.SHARED_NETWORKS.get(name);
        if (network == null) {
            throw new TestAbortedException("Network '" + name + "' is not running");
        }
        network.nodes().forEach(node -> awaitStatus(node, ACTIVE_TIMEOUT, ACTIVE));
        final var test = networkHapiTest(name, network, validation).findFirst().orElseThrow();
        // Validators build their own specs (e.g. to snapshot balances), which target this thread's network
        HapiSpec.TARGET_NETWORK.set(network);
        try {
            test.getExecutable().execute();
        } finally {
            HapiSpec.TARGET_NETWORK.remove();
        }
    }
}
