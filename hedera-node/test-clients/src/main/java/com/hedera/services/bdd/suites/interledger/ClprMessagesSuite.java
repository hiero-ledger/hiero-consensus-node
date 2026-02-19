// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.interledger;

import static com.hedera.services.bdd.spec.HapiSpec.multiNetworkHapiTest;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
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
import org.apache.logging.log4j.Logger;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerConfiguration;
import org.hiero.hapi.interledger.state.clpr.ClprMessageQueueMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(TestTags.MULTINETWORK)
public class ClprMessagesSuite {

    private static final Duration AWAIT_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration AWAIT_POLL_INTERVAL = Duration.ofSeconds(20);

    private static final String PRIVATE_LEDGER = "private";
    private static final String PUBLIC_LEDGER = "public";
    private static final String PUBLIC_LEDGER_S = "smallBundleLedger";
    private static final String PUBLIC_LEDGER_L = "largeBundleLedger";

    @MultiNetworkHapiTest(
            networks = {
                @MultiNetworkHapiTest.Network(
                        name = PRIVATE_LEDGER,
                        size = 1,
                        firstGrpcPort = 35400,
                        setupOverrides = {
                            @ConfigOverride(key = "clpr.clprEnabled", value = "true"),
                            @ConfigOverride(key = "clpr.publicizeNetworkAddresses", value = "false"),
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
    @DisplayName("Messages exchange (public-private)")
    Stream<DynamicTest> publicPrivateMessagesExchange(final SubProcessNetwork netA, final SubProcessNetwork netB) {
        final var configPublicLedger = new AtomicReference<ClprLedgerConfiguration>();
        final var configPrivateLedger = new AtomicReference<ClprLedgerConfiguration>();
        final var messageQueuePrivateLedger = new AtomicReference<ClprMessageQueueMetadata>();
        final var expectedQueueMetadata = ClprMessageQueueMetadata.newBuilder()
                .nextMessageId(21)
                .sentMessageId(20)
                .receivedMessageId(20)
                .build();

        final var builder = multiNetworkHapiTest(netA, netB)
                // get public ledger config
                .onNetwork(PUBLIC_LEDGER, withOpContext((spec, log) -> {
                    final var op = overriding("clpr.publicizeNetworkAddresses", "true");
                    allRunFor(spec, op);
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

                // get latest private network queue
                .onNetwork(PRIVATE_LEDGER, doingContextual(spec -> {
                    // fetch local confing and queue
                    final var ledgerConfig = tryFetchLocalLedgerConfiguration(getFirstNode(spec));
                    configPrivateLedger.set(ledgerConfig);

                    final var messageQueue = tryFetchMessageQueueMetadata(getFirstNode(spec), configPublicLedger.get());
                    messageQueuePrivateLedger.set(messageQueue);
                }))

                // check if private ledger succeed to push its queue to the public ledger
                .onNetwork(PUBLIC_LEDGER, withOpContext((spec, log) -> {
                    // validate the public ledger queue has the expected message id's
                    awaitMatchingCountsMessageQueue(
                            log, spec.getNetworkNodes(), configPrivateLedger.get(), expectedQueueMetadata);
                }))
                .onNetwork(PRIVATE_LEDGER, withOpContext((spec, log) -> {
                    // validate the private ledger queue has the expected message id's
                    awaitMatchingCountsMessageQueue(
                            log, spec.getNetworkNodes(), configPublicLedger.get(), expectedQueueMetadata);
                    awaitEmptyOutgoingQueue(log, spec.getNetworkNodes(), configPublicLedger.get());
                }))
                .onNetwork(PUBLIC_LEDGER, withOpContext((spec, log) -> {
                    // validate the public ledger queue has been fully drained
                    awaitEmptyOutgoingQueue(log, spec.getNetworkNodes(), configPrivateLedger.get());
                }));

        return builder.asDynamicTests();
    }

    @MultiNetworkHapiTest(
            networks = {
                @MultiNetworkHapiTest.Network(
                        name = PUBLIC_LEDGER_L,
                        size = 1,
                        firstGrpcPort = 35400,
                        setupOverrides = {
                            @ConfigOverride(key = "clpr.clprEnabled", value = "true"),
                            @ConfigOverride(key = "clpr.publicizeNetworkAddresses", value = "true"),
                            @ConfigOverride(key = "clpr.maxBundleMessages", value = "10"),
                            @ConfigOverride(key = "clpr.maxBundleBytes", value = "10240"),
                        }),
                @MultiNetworkHapiTest.Network(
                        name = PUBLIC_LEDGER_S,
                        size = 1,
                        firstGrpcPort = 36400,
                        setupOverrides = {
                            @ConfigOverride(key = "clpr.clprEnabled", value = "true"),
                            @ConfigOverride(key = "clpr.publicizeNetworkAddresses", value = "false"),
                            @ConfigOverride(key = "clpr.maxBundleMessages", value = "2"),
                            @ConfigOverride(key = "clpr.maxBundleBytes", value = "6144"),
                        })
            })
    @DisplayName("Respect bundle shape when publish messages")
    Stream<DynamicTest> respectBundleShapeMessagesExchange(final SubProcessNetwork netA, final SubProcessNetwork netB) {
        final var configPublicLedger = new AtomicReference<ClprLedgerConfiguration>();
        final var configPrivateLedger = new AtomicReference<ClprLedgerConfiguration>();
        final var messageQueuePrivateLedger = new AtomicReference<ClprMessageQueueMetadata>();
        final var expectedQueueMetadata = ClprMessageQueueMetadata.newBuilder()
                .nextMessageId(21)
                .sentMessageId(20)
                .receivedMessageId(20)
                .build();

        final var builder = multiNetworkHapiTest(netA, netB)
                // get public ledger config
                .onNetwork(PUBLIC_LEDGER_L, withOpContext((spec, log) -> {
                    final var op = overriding("clpr.publicizeNetworkAddresses", "true");
                    allRunFor(spec, op);
                    final var ledgerConfig = tryFetchLocalLedgerConfiguration(getFirstNode(spec));
                    assertThat(ledgerConfig)
                            .as("Try to fetch config of the public ledger")
                            .isNotNull();
                    configPublicLedger.set(ledgerConfig);
                }))

                // to trigger the exchange submit the public ledger config to the private ledger
                .onNetwork(PUBLIC_LEDGER_S, withOpContext((spec, log) -> {
                    submitConfiguration(spec.targetNetworkOrThrow().nodes().getFirst(), configPublicLedger.get());
                }))

                // get latest private network queue
                .onNetwork(PUBLIC_LEDGER_S, doingContextual(spec -> {
                    // fetch local confing and queue
                    final var ledgerConfig = tryFetchLocalLedgerConfiguration(getFirstNode(spec));
                    configPrivateLedger.set(ledgerConfig);

                    final var messageQueue = tryFetchMessageQueueMetadata(getFirstNode(spec), configPublicLedger.get());
                    messageQueuePrivateLedger.set(messageQueue);
                }))

                // check if private ledger succeed to push its queue to the public ledger
                .onNetwork(PUBLIC_LEDGER_L, withOpContext((spec, log) -> {
                    // validate the public ledger queue has the expected message id's
                    awaitMatchingCountsMessageQueue(
                            log, spec.getNetworkNodes(), configPrivateLedger.get(), expectedQueueMetadata);
                }))
                .onNetwork(PUBLIC_LEDGER_S, withOpContext((spec, log) -> {
                    // validate the private ledger queue has the expected message id's
                    awaitMatchingCountsMessageQueue(
                            log, spec.getNetworkNodes(), configPublicLedger.get(), expectedQueueMetadata);
                    awaitEmptyOutgoingQueue(log, spec.getNetworkNodes(), configPublicLedger.get());
                }))
                .onNetwork(PUBLIC_LEDGER_L, withOpContext((spec, log) -> {
                    // validate the public ledger queue has been fully drained
                    awaitEmptyOutgoingQueue(log, spec.getNetworkNodes(), configPrivateLedger.get());
                }));

        return builder.asDynamicTests();
    }

    private static HederaNode getFirstNode(HapiSpec spec) {
        return spec.getNetworkNodes().getFirst();
    }

    private static void awaitMatchingCountsMessageQueue(
            final Logger log,
            final List<HederaNode> nodes,
            final ClprLedgerConfiguration remoteConfiguration,
            final ClprMessageQueueMetadata expectedMessageQueueMetadata) {
        requireNonNull(nodes);
        requireNonNull(remoteConfiguration);
        final var deadline = Instant.now().plus(AWAIT_TIMEOUT);
        ClprMessageQueueMetadata actual = ClprMessageQueueMetadata.DEFAULT;
        do {
            for (final var node : nodes) {
                actual = tryFetchMessageQueueMetadata(node, remoteConfiguration);
                if (actual != null) {
                    if (matchesMessageQueueCounts(log, expectedMessageQueueMetadata, actual)) {
                        return;
                    }
                }
            }
            try {
                Thread.sleep(AWAIT_POLL_INTERVAL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        } while (Instant.now().isBefore(deadline));

        final var ledgerId = actual.ledgerId().ledgerId().toString();
        log.info("Message queue of ledger {}", ledgerId);
        log.info(
                "receivedMessageId : {}/{}",
                actual.receivedMessageId(),
                expectedMessageQueueMetadata.receivedMessageId());
        log.info("sentMessageId : {}/{}", actual.sentMessageId(), expectedMessageQueueMetadata.sentMessageId());
        log.info("nextMessageId : {}/{}", actual.nextMessageId(), expectedMessageQueueMetadata.nextMessageId());
        throw new IllegalStateException("Timed out waiting for message queue metadata of ledger "
                + remoteConfiguration.ledgerId().ledgerId());
    }

    private static boolean matchesMessageQueueCounts(
            Logger log, ClprMessageQueueMetadata expected, ClprMessageQueueMetadata actual) {
        final var match = actual.receivedMessageId() == expected.receivedMessageId()
                && actual.sentMessageId() == expected.sentMessageId()
                && actual.nextMessageId() == expected.nextMessageId();

        final var ledgerId = actual.ledgerId().ledgerId().toString();
        if (match) {
            log.info("Message queue of ledger {}", ledgerId);
            log.info("receivedMessageId : {}/{}", actual.receivedMessageId(), expected.receivedMessageId());
            log.info("sentMessageId : {}/{}", actual.sentMessageId(), expected.sentMessageId());
            log.info("nextMessageId : {}/{}", actual.nextMessageId(), expected.nextMessageId());
        }
        return match;
    }

    private static void awaitEmptyOutgoingQueue(
            final Logger log, final List<HederaNode> nodes, final ClprLedgerConfiguration remoteConfiguration) {
        requireNonNull(nodes);
        requireNonNull(remoteConfiguration);
        final var deadline = Instant.now().plus(AWAIT_TIMEOUT);
        do {
            for (final var node : nodes) {
                try (final var client = createClient(node)) {
                    final var bundle = client.getMessages(remoteConfiguration.ledgerId(), 1, 1000);
                    if (bundle == null) {
                        return;
                    }
                    log.info(
                            "Message queue for ledger {} not empty yet",
                            remoteConfiguration.ledgerId().ledgerId());
                }
            }
            try {
                Thread.sleep(AWAIT_POLL_INTERVAL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        } while (Instant.now().isBefore(deadline));
        throw new IllegalStateException("Timed out waiting for empty message queue of ledger "
                + remoteConfiguration.ledgerId().ledgerId());
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
