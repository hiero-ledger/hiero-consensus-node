// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.clpr;

import static com.hedera.services.bdd.junit.TestTags.MULTINETWORK;
import static com.hedera.services.bdd.spec.HapiSpec.networkHapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;

import com.hedera.services.bdd.junit.MultiNetworkHapiTest;
import com.hedera.services.bdd.junit.hedera.subprocess.SubProcessNetwork;
import com.hederahashgraph.api.proto.java.ClprEndpoint;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * End-to-end coverage of CLPR sync over the HAPI TLS listener. Unlike the plaintext
 * {@link ClprHieroToHieroSuite}, both networks start their HAPI TLS listener with a self-signed certificate
 * ({@code enableHapiTls = true}), so each node advertises its {@code grpc.tlsPort} and that certificate in its
 * {@code ClprEndpoint}. A delivered message therefore proves the TLS path: the peer read a non-empty
 * {@code tls_certificate}, dialed the HAPI TLS port over TLS, and the bytes crossed that listener.
 */
@Tag(MULTINETWORK)
public class ClprHieroToHieroTlsSuite extends HieroToHieroBase {

    private static final int HAPI_TLS_PORT_A = 41450;
    private static final int HAPI_TLS_PORT_B = 42450;

    /** {@code ClprHapiTlsEndpoint} logs this when a node has no HAPI TLS certificate to advertise. */
    private static final Pattern PLAINTEXT_FALLBACK_PATTERN =
            Pattern.compile("CLPR sync will be advertised on the plain HAPI port");

    @MultiNetworkHapiTest(
            value = {
                @MultiNetworkHapiTest.Network(
                        name = "ledgerA_tls",
                        enableHapiTls = true,
                        firstHapiTlsPort = HAPI_TLS_PORT_A),
                @MultiNetworkHapiTest.Network(
                        name = "ledgerB_tls",
                        enableHapiTls = true,
                        firstHapiTlsPort = HAPI_TLS_PORT_B)
            })
    @DisplayName("given HAPI TLS on both ledgers, when A sends a message, then it reaches B over the HAPI TLS port")
    Stream<DynamicTest> givenHapiTlsOnBothLedgers_whenASendsMessage_thenItReachesBOverHapiTlsPort(
            final SubProcessNetwork ledgerA, final SubProcessNetwork ledgerB) {
        final var crypto = new ClprCrypto();

        return multiNetworkHapiTest(
                "tlsOneWayDelivery",
                Stream.concat(
                        setupBothNetworks(
                                ledgerA,
                                ledgerB,
                                HAPI_TLS_PORT_A,
                                HAPI_TLS_PORT_B,
                                crypto,
                                DEFAULT_MAX_MESSAGES_PER_BUNDLE,
                                DEFAULT_MAX_QUEUE_DEPTH),
                        Stream.of(
                                // Each node started its HAPI TLS listener on the configured port (TLS was
                                // actually enabled, not skipped for a missing certificate).
                                awaitHapiTlsListener(ledgerA, HAPI_TLS_PORT_A),
                                awaitHapiTlsListener(ledgerB, HAPI_TLS_PORT_B),
                                // Each ledger's manifest advertises the HAPI TLS port and a certificate, which
                                // is what makes the peer dial it over TLS.
                                awaitManifestAdvertisesHapiTls(ledgerA, HAPI_TLS_PORT_A),
                                awaitManifestAdvertisesHapiTls(ledgerB, HAPI_TLS_PORT_B),
                                // Send one message from A; a receive on B + ack on A proves the bytes crossed
                                // both ledgers' HAPI TLS listeners.
                                networkHapiTest(
                                                "Send 'hello-tls' from A over the HAPI TLS port",
                                                ledgerA,
                                                cryptoCreate("callerA").balance(ONE_HUNDRED_HBARS),
                                                uploadInitCode(CLPR_CONTRACT),
                                                contractCreate(CLPR_CONTRACT),
                                                contractCall(
                                                                CLPR_CONTRACT,
                                                                SEND_MESSAGE,
                                                                crypto.channelId,
                                                                crypto.connectorId,
                                                                new byte[20],
                                                                "hello-tls".getBytes(StandardCharsets.UTF_8))
                                                        .gas(GAS)
                                                        .payingWith("callerA"))
                                        .findFirst()
                                        .orElseThrow(),
                                awaitReceivedMessage(ledgerB, crypto.channelId, 1),
                                awaitAckedMessage(ledgerA, crypto.channelId, 1),
                                // Neither side fell back to advertising its plain HAPI port (which would mean
                                // TLS was not really exercised).
                                networkHapiTest(
                                                "Assert neither side fell back to the plain HAPI port",
                                                ledgerA,
                                                withOpContext((spec, opLog) -> {
                                                    assertLogLineAbsent(ledgerA, PLAINTEXT_FALLBACK_PATTERN);
                                                    assertLogLineAbsent(ledgerB, PLAINTEXT_FALLBACK_PATTERN);
                                                }))
                                        .findFirst()
                                        .orElseThrow())));
    }

    private static DynamicTest awaitHapiTlsListener(final SubProcessNetwork network, final int port) {
        return networkHapiTest(
                        "Assert " + network.name() + " started its HAPI TLS listener on " + port,
                        network,
                        withOpContext((spec, opLog) -> awaitLogLine(
                                network,
                                Pattern.compile("TLS gRPC server listening on port " + port),
                                Duration.ofSeconds(30))))
                .findFirst()
                .orElseThrow();
    }

    private static DynamicTest awaitManifestAdvertisesHapiTls(final SubProcessNetwork network, final int basePort) {
        final int endPort = basePort + network.nodes().size();
        return networkHapiTest(
                        "Assert " + network.name() + "'s manifest advertises its HAPI TLS endpoints",
                        network,
                        withOpContext((spec, opLog) -> pollManifest(
                                spec,
                                m -> m.getVersion() >= FINALIZED_MANIFEST_MIN_VERSION
                                        && m.getEndpointsCount()
                                                == network.nodes().size()
                                        && m.getEndpointsList().stream()
                                                .allMatch(e -> advertisesHapiTls(e, basePort, endPort)))))
                .findFirst()
                .orElseThrow();
    }

    private static boolean advertisesHapiTls(final ClprEndpoint endpoint, final int basePort, final int endPort) {
        final int port = endpoint.getServiceEndpoint().getPort();
        return port >= basePort
                && port < endPort
                && !endpoint.getTlsCertificate().isEmpty();
    }
}
