// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.interledger;

import static com.hedera.services.bdd.spec.HapiSpec.multiNetworkHapiTest;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.interledger.ClprSuite.createClient;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.hiero.interledger.clpr.ClprStateProofUtils.buildLocalClprStateProofWrapper;
import static org.hiero.interledger.clpr.ClprStateProofUtils.extractConfiguration;
import static org.hiero.interledger.clpr.ClprStateProofUtils.extractMessageQueueMetadata;

import com.hedera.services.bdd.junit.ConfigOverride;
import com.hedera.services.bdd.junit.MultiNetworkHapiTest;
import com.hedera.services.bdd.junit.TestTags;
import com.hedera.services.bdd.junit.hedera.HederaNode;
import com.hedera.services.bdd.junit.hedera.subprocess.SubProcessNetwork;
import com.hedera.services.bdd.spec.HapiSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerConfiguration;
import org.hiero.hapi.interledger.state.clpr.ClprMessageQueueMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(TestTags.MULTINETWORK)
public class ClprMessagesSuite {

    private static final Duration PROPAGATION_TIMEOUT = Duration.ofMinutes(1);
    private static final Duration PROPAGATION_POLL_INTERVAL = Duration.ofSeconds(1);

    private static final String PRIVATE_LEDGER = "private";
    private static final String PUBLIC_LEDGER = "public";

    @MultiNetworkHapiTest(
            networks = {
                @MultiNetworkHapiTest.Network(
                        name = PRIVATE_LEDGER,
                        size = 1,
                        firstGrpcPort = 35400,
                        setupOverrides = {
                            @ConfigOverride(key = "clpr.clprEnabled", value = "true"),
                            @ConfigOverride(key = "clpr.publicizeNetworkAddresses", value = "false"),
                            @ConfigOverride(key = "clpr.connectionFrequency", value = "1000")
                        }),
                @MultiNetworkHapiTest.Network(
                        name = PUBLIC_LEDGER,
                        size = 1,
                        firstGrpcPort = 36400,
                        setupOverrides = {
                            @ConfigOverride(key = "clpr.clprEnabled", value = "true"),
                            @ConfigOverride(key = "clpr.publicizeNetworkAddresses", value = "true"),
                        })
            })
    @DisplayName("Two-network messages exchange")
    Stream<DynamicTest> twoNetworkMessagesExchange(final SubProcessNetwork netA, final SubProcessNetwork netB) {
        final var configPublicLedger = new AtomicReference<ClprLedgerConfiguration>();
        final var configPrivateLedger = new AtomicReference<ClprLedgerConfiguration>();
        final var messageQueuePrivateLedger = new AtomicReference<ClprMessageQueueMetadata>();

        final var builder = multiNetworkHapiTest(netA, netB)
                // get public ledger config
                .onNetwork(PUBLIC_LEDGER, withOpContext((spec, log) -> {
                    final var ledgerConfig = tryFetchLocalLedgerConfiguration(getFirstNode(spec));
                    assertThat(ledgerConfig)
                            .as("Try to fetch config of the public ledger")
                            .isNotNull();
                    configPublicLedger.set(ledgerConfig);
                }))

                // to trigger the exchange submit the public ledger config to the private ledger
                .onNetwork(PRIVATE_LEDGER, withOpContext((spec, log) -> {
                    submitConfiguration(spec.targetNetworkOrThrow().nodes().getFirst(), configPublicLedger.get());
                }))

                // wait all messages to be exchanged
                .onNetwork(PRIVATE_LEDGER, sleepFor(10000))

                // get latest private network queue
                .onNetwork(PRIVATE_LEDGER, doingContextual(spec -> {
                    // fetch local confing and queue
                    final var ledgerConfig = tryFetchLocalLedgerConfiguration(getFirstNode(spec));
                    final var messageQueue = tryFetchMessageQueueMetadata(getFirstNode(spec), ledgerConfig);
                    configPrivateLedger.set(ledgerConfig);
                    messageQueuePrivateLedger.set(messageQueue);
                }))

                // check if private ledger succeed to push its queue to the public ledger
                .onNetwork(PUBLIC_LEDGER, withOpContext((spec, log) -> {
                    final var expectedQueueMetadata = ClprMessageQueueMetadata.newBuilder()
                            .nextMessageId(5)
                            .sentMessageId(0)
                            .receivedMessageId(5) //
                            .build();

                    awaitMatchingMessageQueueMetadata(
                            // on public nodes
                            spec.getNetworkNodes(),
                            // fetch private  queue
                            configPrivateLedger.get(),
                            // expect the private queue
                            expectedQueueMetadata,
                            // timeout intervals
                            PROPAGATION_TIMEOUT,
                            PROPAGATION_POLL_INTERVAL);
                }));

        return builder.asDynamicTests();
    }

    private static HederaNode getFirstNode(HapiSpec spec) {
        return spec.getNetworkNodes().getFirst();
    }

    // TODO Enhance this assertion
    private static ClprMessageQueueMetadata awaitMatchingMessageQueueMetadata(
            final List<HederaNode> nodes,
            final ClprLedgerConfiguration remoteConfiguration,
            final ClprMessageQueueMetadata expectedMessageQueueMetadata,
            final Duration timeout,
            final Duration pollInterval) {
        requireNonNull(nodes);
        requireNonNull(remoteConfiguration);
        requireNonNull(timeout);
        requireNonNull(pollInterval);
        final var deadline = Instant.now().plus(timeout);
        do {
            for (final var node : nodes) {
                final var msgQueueMetadata = tryFetchMessageQueueMetadata(node, remoteConfiguration);
                if (msgQueueMetadata != null) {
                    if (matchesMessageQueueMetadata(expectedMessageQueueMetadata, msgQueueMetadata)) {
                        return msgQueueMetadata;
                    }
                }
            }
            try {
                Thread.sleep(pollInterval.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        } while (Instant.now().isBefore(deadline));
        throw new IllegalStateException("Timed out waiting for message queue metadata of ledger "
                + remoteConfiguration.ledgerId().ledgerId());
    }

    private static boolean matchesMessageQueueMetadata(
            ClprMessageQueueMetadata expected, ClprMessageQueueMetadata actual) {
        return (actual.receivedMessageId() == expected.receivedMessageId()) && (actual.sentMessageId() == expected.sentMessageId());
    }

    private static ClprMessageQueueMetadata tryFetchMessageQueueMetadata(
            HederaNode node, ClprLedgerConfiguration clprLedgerConfiguration) {
        try (final var client = createClient(node)) {
            final var proof = client.getMessageQueueMetadata(clprLedgerConfiguration.ledgerId());
            if (proof == null) {
                return null;
            }
            return extractMessageQueueMetadata(proof);
        }
    }

    private static ClprLedgerConfiguration tryFetchLocalLedgerConfiguration(final HederaNode node) {
        try (final var client = createClient(node)) {
            final var proof = client.getConfiguration();
            if (proof == null) {
                return null;
            }
            return extractConfiguration(proof);
        }
    }

    private static void submitConfiguration(final HederaNode node, final ClprLedgerConfiguration configuration) {
        final var payer = node.getAccountId();
        final var proof = buildLocalClprStateProofWrapper(configuration);
        try (var client = createClient(node)) {
            client.setConfiguration(payer, payer, proof);
        }
    }
}
