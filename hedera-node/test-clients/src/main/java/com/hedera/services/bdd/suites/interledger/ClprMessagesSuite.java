// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.interledger;

import static com.hedera.node.app.hapi.utils.CommonPbjConverters.toPbj;
import static com.hedera.services.bdd.spec.HapiSpec.multiNetworkHapiTest;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.interledger.ClprSuite.createClient;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.ConfigOverride;
import com.hedera.services.bdd.junit.MultiNetworkHapiTest;
import com.hedera.services.bdd.junit.TestTags;
import com.hedera.services.bdd.junit.hedera.HederaNode;
import com.hedera.services.bdd.junit.hedera.subprocess.SubProcessNetwork;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.hiero.hapi.interledger.state.clpr.ClprMessageQueueMetadata;
import org.hiero.hapi.interledger.state.clpr.protoc.ClprLedgerConfiguration;
import org.hiero.interledger.clpr.ClprStateProofUtils;
import org.hiero.interledger.clpr.impl.client.ClprClientImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(TestTags.MULTINETWORK)
public class ClprMessagesSuite {

    private static final Duration PROPAGATION_TIMEOUT = Duration.ofMinutes(1);
    private static final Duration PROPAGATION_POLL_INTERVAL = Duration.ofSeconds(1);

    private static final String LEDGER_A = "ledger-A";
    private static final String LEDGER_B = "ledger-B";

    @MultiNetworkHapiTest(
            networks = {
                @MultiNetworkHapiTest.Network(
                        name = LEDGER_A,
                        size = 1,
                        firstGrpcPort = 35400,
                        setupOverrides = {
                            @ConfigOverride(key = "clpr.clprEnabled", value = "true"),
                            @ConfigOverride(key = "clpr.publicizeNetworkAddresses", value = "true"),
                            @ConfigOverride(key = "clpr.connectionFrequency", value = "1000")
                        }),
                @MultiNetworkHapiTest.Network(
                        name = LEDGER_B,
                        size = 1,
                        firstGrpcPort = 36400,
                        setupOverrides = {
                            @ConfigOverride(key = "clpr.clprEnabled", value = "true"),
                            @ConfigOverride(key = "clpr.publicizeNetworkAddresses", value = "true"),
                            @ConfigOverride(key = "clpr.connectionFrequency", value = "1000")
                        })
            })
    @DisplayName("Two-network messages exchange")
    Stream<DynamicTest> twoNetworkMessagesExchange(final SubProcessNetwork netA, final SubProcessNetwork netB) {
        final var configA = new AtomicReference<ClprLedgerConfiguration>();
        final var messageQueueMetadataA = new AtomicReference<ClprMessageQueueMetadata>();

        final var builder = multiNetworkHapiTest(netA, netB)
                .onNetwork(LEDGER_A, withOpContext((spec, log) -> {
                    final var node = spec.targetNetworkOrThrow().nodes().getFirst();

                    // fetch and store ledger A config
                    final var config = tryFetchLedgerConfiguration(node);
                    assertThat(config).as("Try to fetch config of ledger A").isNotNull();
                    configA.set(config);

                    // fetch and store ledger A message queue metadata
                    final var messageQueueMetadata = fetchMessageQueueMetadata(node, config);
                    assertThat(messageQueueMetadata)
                            .as("Try to fetch config of ledger A")
                            .isNotNull();
                    messageQueueMetadataA.set(messageQueueMetadata);
                }))

                // Stage 2: Submit LEDGER_A's config to LEDGER_B to trigger publishing.
                .onNetwork(LEDGER_B, withOpContext((spec, opLog) -> {
                    submitConfiguration(spec.targetNetworkOrThrow().nodes().getFirst(), configA.get());
                }))

                // Stage 3: Verify LEDGER_A message queue is stored on LEDGER_B and LEDGER_B message queue arrives on
                // LEDGER_A.
                .onNetwork(LEDGER_A, sleepFor(5000))
                .onNetwork(LEDGER_B, withOpContext((spec, log) -> {
                    final var messageQueueMetadata = messageQueueMetadataA.get();
                    awaitMatchingMessageQueueMetadata(
                            spec.getNetworkNodes(),
                            configA.get(),
                            messageQueueMetadata,
                            PROPAGATION_TIMEOUT,
                            PROPAGATION_POLL_INTERVAL);
                }));

        return builder.asDynamicTests();
    }

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
                final var msgQueueMetadata = fetchMessageQueueMetadata(node, remoteConfiguration);
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
                + remoteConfiguration.getLedgerId().getLedgerId().toStringUtf8());
    }

    private static boolean matchesMessageQueueMetadata(
            ClprMessageQueueMetadata expected, ClprMessageQueueMetadata actual) {
        return expected.equals(actual);
    }

    private static ClprMessageQueueMetadata fetchMessageQueueMetadata(
            HederaNode node, ClprLedgerConfiguration clprLedgerConfiguration) {
        // TODO: handle null
        final var client = createClient(node);
        final var config = toPbj(clprLedgerConfiguration);
        return client.getMessageQueueMetadata(config.ledgerId());
    }

    // TODO: Extract duplicated code in util or verb classes. Or create Base test class.
    private static ClprLedgerConfiguration tryFetchLedgerConfiguration(final HederaNode node) {
        try {
            final var client = new ClprClientImpl(toPbjEndpoint(node));
            final var proof = client.getConfiguration();
            if (proof == null) {
                return null;
            }
            final var pbjConfig = ClprStateProofUtils.extractConfiguration(proof);
            final var configBytes =
                    org.hiero.hapi.interledger.state.clpr.ClprLedgerConfiguration.PROTOBUF.toBytes(pbjConfig);
            return ClprLedgerConfiguration.parseFrom(configBytes.toByteArray());
        } catch (UnknownHostException | com.google.protobuf.InvalidProtocolBufferException e) {
            throw new IllegalStateException("Unable to fetch CLPR ledger configuration", e);
        }
    }

    private static com.hedera.hapi.node.base.ServiceEndpoint toPbjEndpoint(final HederaNode node)
            throws UnknownHostException {
        return com.hedera.hapi.node.base.ServiceEndpoint.newBuilder()
                .ipAddressV4(Bytes.wrap(InetAddress.getByName(node.getHost()).getAddress()))
                .port(node.getGrpcPort())
                .build();
    }

    private static void submitConfiguration(final HederaNode node, final ClprLedgerConfiguration protocConfig) {
        final var payer = node.getAccountId();
        final var pbjConfig = toPbjConfig(protocConfig);
        final var proof = ClprStateProofUtils.buildLocalClprStateProofWrapper(pbjConfig);
        try (var client = new ClprClientImpl(toPbjEndpoint(node))) {
            client.setConfiguration(payer, payer, proof);
        } catch (final UnknownHostException e) {
            throw new IllegalStateException("Unable to resolve CLPR endpoint for node " + node.getName(), e);
        }
    }

    private static org.hiero.hapi.interledger.state.clpr.ClprLedgerConfiguration toPbjConfig(
            final ClprLedgerConfiguration config) {
        try {
            return org.hiero.hapi.interledger.state.clpr.ClprLedgerConfiguration.PROTOBUF.parse(
                    Bytes.wrap(config.toByteArray()).toReadableSequentialData());
        } catch (final ParseException e) {
            throw new IllegalStateException("Unable to parse protoc configuration to PBJ", e);
        }
    }
}
