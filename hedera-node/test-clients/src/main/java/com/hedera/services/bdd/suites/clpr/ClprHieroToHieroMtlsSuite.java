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

import com.hedera.services.bdd.junit.ConfigOverride;
import com.hedera.services.bdd.junit.MultiNetworkHapiTest;
import com.hedera.services.bdd.junit.extensions.MultiNetworkExtension;
import com.hedera.services.bdd.junit.hedera.subprocess.SubProcessNetwork;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * End-to-end coverage of CLPR sync over the dedicated mutual-TLS listener. Unlike the plaintext
 * {@link ClprHieroToHieroSuite}, both networks are provisioned with a real ECDSA P-384 CLPR CA
 * ({@code enableClprMtls = true}) whose cert is advertised on-chain in {@code ClprEndpoint.tls_certificate}
 * and whose endpoint points at each network's {@code clpr.mtlsPort}. A delivered message therefore
 * proves the full mTLS path: this node's ephemeral leaf presented under {@code ClientAuth.REQUIRE},
 * the peer pinning the on-chain CA, and bytes actually crossing the {@code mtlsPort} listener.
 */
@Tag(MULTINETWORK)
public class ClprHieroToHieroMtlsSuite extends HieroToHieroBase {

    private static final int MTLS_PORT_A = 41450;
    private static final int MTLS_PORT_B = 42450;

    /** {@code ClprSynchronizerImpl} logs this when mTLS is on but a peer has no advertised CA. */
    private static final Pattern SKIP_PATTERN = Pattern.compile("Skipping sync peer .* no tls_certificate");

    @MultiNetworkHapiTest(
            value = {
                @MultiNetworkHapiTest.Network(
                        name = "ledgerA_mtls",
                        enableClprMtls = true,
                        setupOverrides = {
                            @ConfigOverride(key = "clpr.caCrtPath", value = "data/clpr/ca.crt"),
                            @ConfigOverride(key = "clpr.caKeyPath", value = "data/clpr/ca.key"),
                            @ConfigOverride(key = "clpr.mtlsPort", value = "41450")
                        }),
                @MultiNetworkHapiTest.Network(
                        name = "ledgerB_mtls",
                        enableClprMtls = true,
                        setupOverrides = {
                            @ConfigOverride(key = "clpr.caCrtPath", value = "data/clpr/ca.crt"),
                            @ConfigOverride(key = "clpr.caKeyPath", value = "data/clpr/ca.key"),
                            @ConfigOverride(key = "clpr.mtlsPort", value = "42450")
                        })
            })
    @DisplayName("mTLS one-way: message from ledger A crosses to ledger B over the dedicated mTLS listener")
    Stream<DynamicTest> mtlsOneWayDelivery(final SubProcessNetwork ledgerA, final SubProcessNetwork ledgerB) {
        final var crypto = new ClprCrypto();
        final byte[] caDerA = MultiNetworkExtension.clprMtlsCaDer(ledgerA.name());
        final byte[] caDerB = MultiNetworkExtension.clprMtlsCaDer(ledgerB.name());

        return Stream.concat(
                // Advertise each network's real CA cert + its mtlsPort as the endpoint, so the
                // channel is completed against — and syncs over — the dedicated mTLS listener.
                setupBothNetworks(
                        ledgerA,
                        ledgerB,
                        MTLS_PORT_A,
                        MTLS_PORT_B,
                        crypto,
                        DEFAULT_MAX_MESSAGES_PER_BUNDLE,
                        DEFAULT_MAX_QUEUE_DEPTH,
                        caDerA,
                        caDerB),
                Stream.of(
                        // Positive: each node started its dedicated CLPR mTLS sync listener on the
                        // advertised port (mTLS was actually enabled, not skipped for a missing CA).
                        networkHapiTest(
                                        "Assert A started the mTLS sync listener on " + MTLS_PORT_A,
                                        ledgerA,
                                        withOpContext((spec, opLog) -> awaitLogLine(
                                                ledgerA,
                                                Pattern.compile(
                                                        "Starting CLPR mTLS sync gRPC server on port " + MTLS_PORT_A),
                                                Duration.ofSeconds(30))))
                                .findFirst()
                                .orElseThrow(),
                        networkHapiTest(
                                        "Assert B started the mTLS sync listener on " + MTLS_PORT_B,
                                        ledgerB,
                                        withOpContext((spec, opLog) -> awaitLogLine(
                                                ledgerB,
                                                Pattern.compile(
                                                        "Starting CLPR mTLS sync gRPC server on port " + MTLS_PORT_B),
                                                Duration.ofSeconds(30))))
                                .findFirst()
                                .orElseThrow(),
                        // Send one message from A; a receive on B + ack on A proves the bytes crossed
                        // the mutual-TLS handshake (peer pinned A's on-chain CA, A presented its leaf).
                        networkHapiTest(
                                        "Send 'hello-mtls' from A over the mTLS listener",
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
                                                        "hello-mtls".getBytes(StandardCharsets.UTF_8))
                                                .gas(GAS)
                                                .payingWith("callerA"))
                                .findFirst()
                                .orElseThrow(),
                        awaitReceivedMessage(ledgerB, crypto.channelId, 1),
                        awaitAckedMessage(ledgerA, crypto.channelId, 1),
                        // Negative: neither side fell back to the plaintext skip (which would mean a
                        // peer's tls_certificate was empty, i.e. mTLS was not really exercised).
                        networkHapiTest(
                                        "Assert neither side skipped a peer for a missing tls_certificate",
                                        ledgerA,
                                        withOpContext((spec, opLog) -> {
                                            assertLogLineAbsent(ledgerA, SKIP_PATTERN);
                                            assertLogLineAbsent(ledgerB, SKIP_PATTERN);
                                        }))
                                .findFirst()
                                .orElseThrow()));
    }
}
