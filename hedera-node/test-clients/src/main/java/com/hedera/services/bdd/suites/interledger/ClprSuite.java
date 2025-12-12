// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.interledger;

import static com.hedera.services.bdd.spec.HapiSpec.customizedHapiTest;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;

import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.TestTags;
import com.hedera.services.bdd.junit.hedera.ExternalPath;
import com.hedera.services.bdd.spec.queries.QueryVerbs;
import com.hedera.services.bdd.spec.transactions.TxnVerbs;
import com.hederahashgraph.api.proto.java.Timestamp;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.hiero.hapi.interledger.state.clpr.ClprEndpoint;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerConfiguration;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerId;
import org.hiero.interledger.clpr.impl.client.ClprClientImpl;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(TestTags.CLPR)
public class ClprSuite extends AbstractClprSuite {
    @org.junit.jupiter.api.BeforeEach
    void initDefaults() {
        setConfigDefaults();
    }

    private static final Duration CONFIG_APPLY_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration POLL_DELAY = Duration.ofMillis(200);
    private static final Duration LOG_WAIT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration LOG_POLL_INTERVAL = Duration.ofMillis(250);
    private static final String ENDPOINT_SUCCESS_PREFIX = "CLPR Endpoint: Submitted configuration";
    private static final String BOOTSTRAP_PREFIX = "CLPR Endpoint: Bootstrapped local ledger";
    private static final String FETCH_FAILURE_MESSAGE = "CLPR Endpoint: Failed to fetch local configuration via client";
    private static final String INVALID_STATUS_TOKEN = "(status=FAIL_INVALID)";
    private static final String INVALID_BODY_STATUS_TOKEN = "(status=INVALID_TRANSACTION_BODY)";

    @HapiTest
    final Stream<DynamicTest> createsClprLedgerConfig() {
        final var now = Instant.now();
        return customizedHapiTest(
                Map.of("clpr.connectionFrequency", "60000"),
                TxnVerbs.clprSetLedgerConfig("ledgerId")
                        .timestamp(Timestamp.newBuilder()
                                .setSeconds(now.getEpochSecond())
                                .setNanos(now.getNano())
                                .build()));
    }

    @HapiTest
    @DisplayName("Updates configurations via ClprClientImpl")
    final Stream<DynamicTest> updatesConfigurationsViaClprClientImpl() {
        final var ledgerIdString = "clpr-ledger-" + Instant.now().toEpochMilli();
        final var ledgerId = ClprLedgerId.newBuilder()
                .ledgerId(Bytes.wrap(ledgerIdString.getBytes(StandardCharsets.UTF_8)))
                .build();
        final Instant baseInstant = Instant.now();
        final Instant firstInstant = baseInstant.plusSeconds(1);
        final Instant secondInstant = firstInstant.plusSeconds(1);
        final Instant thirdInstant = secondInstant.plusSeconds(1);

        return customizedHapiTest(
                Map.of("clpr.connectionFrequency", "60000"),
                withOpContext((spec, log) -> {
                    final var node = spec.targetNetworkOrThrow().nodes().getFirst();
                    final ServiceEndpoint serviceEndpoint;
                    try {
                        final var addressBytes =
                                InetAddress.getByName(node.getHost()).getAddress();
                        final int port = node.getGrpcPort();
                        serviceEndpoint = ServiceEndpoint.newBuilder()
                                .ipAddressV4(Bytes.wrap(addressBytes))
                                .port(port)
                                .build();
                    } catch (final UnknownHostException e) {
                        throw new IllegalStateException("Unable to resolve node host for CLPR endpoint", e);
                    }
                    final var payerAccountId = node.getAccountId();
                    final var endpoints = List.of(ClprEndpoint.newBuilder()
                            .endpoint(serviceEndpoint)
                            .signingCertificate(Bytes.wrap("test-cert".getBytes(StandardCharsets.UTF_8)))
                            .build());

                    try (var client = new ClprClientImpl(serviceEndpoint)) {
                        Assertions.assertNull(client.getConfiguration(ledgerId));

                        final var firstConfig = buildConfiguration(ledgerId, firstInstant, endpoints);
                        Assertions.assertEquals(
                                ResponseCodeEnum.OK,
                                client.setConfiguration(payerAccountId, node.getAccountId(), firstConfig));
                        final var storedFirst = awaitConfiguration(client, ledgerId, firstConfig);
                        assertConfigurationAtLeast(firstConfig, storedFirst);

                        final var secondConfig = buildConfiguration(ledgerId, secondInstant, endpoints);
                        Assertions.assertEquals(
                                ResponseCodeEnum.OK,
                                client.setConfiguration(payerAccountId, node.getAccountId(), secondConfig));
                        final var storedSecond = awaitConfiguration(client, ledgerId, secondConfig);
                        assertConfigurationAtLeast(secondConfig, storedSecond);

                        final var thirdConfig = buildConfiguration(ledgerId, thirdInstant, endpoints);
                        Assertions.assertEquals(
                                ResponseCodeEnum.OK,
                                client.setConfiguration(payerAccountId, node.getAccountId(), thirdConfig));
                        final var storedThird = awaitConfiguration(client, ledgerId, thirdConfig);
                        assertConfigurationAtLeast(thirdConfig, storedThird);
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Interrupted while awaiting CLPR configuration", e);
                    }
                }),
                sourcing(() -> QueryVerbs.getLedgerConfig(ledgerIdString).hasTimestamp(thirdInstant.getEpochSecond())));
    }

    private static ClprLedgerConfiguration awaitConfiguration(
            final ClprClientImpl client, final ClprLedgerId ledgerId, final ClprLedgerConfiguration expected)
            throws InterruptedException {
        final long deadlineNanos = System.nanoTime() + CONFIG_APPLY_TIMEOUT.toNanos();
        ClprLedgerConfiguration current;
        System.out.printf(
                "CLPR await: expecting ledger %s ts %s endpoints %s%n",
                expected.ledgerId().ledgerId(), expected.timestamp().seconds(), expected.endpoints());
        String lastObserved = null;
        while (true) {
            current = client.getConfiguration(ledgerId);
            final String summary = current == null ? "null" : summarize(current);
            if (!summary.equals(lastObserved)) {
                System.out.printf("CLPR await: observed %s%n", summary);
                lastObserved = summary;
            }
            if (isAtLeastExpected(expected, current)) {
                return current;
            }
            if (System.nanoTime() >= deadlineNanos) {
                throw new IllegalStateException("Timed out waiting for CLPR configuration update");
            }
            Thread.sleep(POLL_DELAY.toMillis());
        }
    }

    private static boolean isAtLeastExpected(
            final ClprLedgerConfiguration expected, final ClprLedgerConfiguration current) {
        if (current == null) {
            return false;
        }
        if (!expected.ledgerId().equals(current.ledgerId())) {
            return false;
        }
        if (!expected.endpoints().equals(current.endpoints())) {
            return false;
        }
        final var expectedTs = expected.timestamp();
        final var currentTs = current.timestamp();
        return currentTs.seconds() > expectedTs.seconds()
                || (currentTs.seconds() == expectedTs.seconds() && currentTs.nanos() >= expectedTs.nanos());
    }

    private static String summarize(final ClprLedgerConfiguration config) {
        return "ledger="
                + config.ledgerId().ledgerId()
                + " ts="
                + config.timestamp().seconds()
                + "."
                + config.timestamp().nanos()
                + " endpoints="
                + config.endpoints();
    }

    private static void assertConfigurationAtLeast(
            final ClprLedgerConfiguration expected, final ClprLedgerConfiguration actual) {
        Assertions.assertNotNull(actual, "CLPR configuration not present");
        Assertions.assertEquals(expected.ledgerId(), actual.ledgerId(), "ledgerId mismatch");
        Assertions.assertEquals(expected.endpoints(), actual.endpoints(), "endpoints mismatch");
        final var expectedTs = expected.timestamp();
        final var actualTs = actual.timestamp();
        Assertions.assertTrue(
                actualTs.seconds() > expectedTs.seconds()
                        || (actualTs.seconds() == expectedTs.seconds() && actualTs.nanos() >= expectedTs.nanos()),
                "timestamp did not advance to at least expected");
    }

    private static ClprLedgerConfiguration buildConfiguration(
            final ClprLedgerId ledgerId, final Instant instant, final List<ClprEndpoint> endpoints) {
        return ClprLedgerConfiguration.newBuilder()
                .ledgerId(ledgerId)
                .timestamp(com.hedera.hapi.node.base.Timestamp.newBuilder()
                        .seconds(instant.getEpochSecond())
                        .nanos(instant.getNano())
                        .build())
                .endpoints(endpoints)
                .build();
    }

    private static void awaitLogContains(
            final com.hedera.services.bdd.spec.HapiSpec spec, final String substring, final Duration timeout)
            throws InterruptedException {
        final Path logPath = swirldsLog(spec);
        final long deadlineNanos = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadlineNanos) {
            if (Files.exists(logPath)) {
                try {
                    if (Files.readString(logPath, StandardCharsets.UTF_8).contains(substring)) {
                        return;
                    }
                } catch (final IOException e) {
                    throw new IllegalStateException("Failed reading log file " + logPath, e);
                }
            }
            Thread.sleep(LOG_POLL_INTERVAL.toMillis());
        }
        throw new IllegalStateException("Timed out waiting for log entry '" + substring + "' in " + logPath);
    }

    private static void assertLogDoesNotContain(
            final com.hedera.services.bdd.spec.HapiSpec spec, final String substring) {
        final Path logPath = swirldsLog(spec);
        if (!Files.exists(logPath)) {
            return;
        }
        try {
            if (Files.readString(logPath, StandardCharsets.UTF_8).contains(substring)) {
                throw new IllegalStateException("Observed unexpected log entry '" + substring + "' in " + logPath);
            }
        } catch (final IOException e) {
            throw new IllegalStateException("Failed reading log file " + logPath, e);
        }
    }

    private static Path swirldsLog(final com.hedera.services.bdd.spec.HapiSpec spec) {
        return spec.targetNetworkOrThrow().nodes().getFirst().getExternalPath(ExternalPath.SWIRLDS_LOG);
    }
}
