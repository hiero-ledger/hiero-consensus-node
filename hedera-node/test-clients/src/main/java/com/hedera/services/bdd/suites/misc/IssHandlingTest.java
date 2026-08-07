// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.misc;

import static com.hedera.services.bdd.junit.TestTags.ISS;
import static com.hedera.services.bdd.junit.hedera.ExternalPath.APPLICATION_PROPERTIES;
import static com.hedera.services.bdd.junit.hedera.ExternalPath.DATA_CONFIG_DIR;
import static com.hedera.services.bdd.junit.hedera.NodeSelector.byNodeId;
import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.updateBootstrapProperties;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getVersionInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertHgcaaLogContainsText;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertHgcaaLogDoesNotContainText;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.freezeOnly;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepForSeconds;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.untilHgcaaLogContainsText;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.verify;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitForFrozenNetwork;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.crypto.ParseableIssBlockStreamValidationOp.ISS_NODE_ID;
import static com.hedera.services.bdd.suites.regression.system.LifecycleTest.configVersionOf;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.hedera.NodeSelector;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.suites.crypto.ParseableIssBlockStreamValidationOp;
import com.hedera.services.bdd.suites.regression.system.LifecycleTest;
import com.hederahashgraph.api.proto.java.SemanticVersion;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Validates ISS detection works by reconnecting {@code node1} with an artificially low override for
 * {@code ledger.transfers.maxLen}, then submitting a {@link com.hedera.hapi.node.token.CryptoTransferTransactionBody}
 * that exceeds that artificial limit.
 * <p>
 * This should cause an ISS to be detected in {@code node1}. The block stream manager is <i>not</i> stopped at ISS
 * detection; {@code node1} keeps streaming until the platform reaches {@code CATASTROPHIC_FAILURE}, at which point the
 * manager flushes the contents of any open/pending blocks to disk for triage. The remaining nodes
 * should still be able to handle transactions and freeze the network.
 * <p>
 * The test also enables the failure-block-upload feature on {@code node1} pointed at an in-JVM S3 mock (no Docker) and
 * asserts that both uploads reach the endpoint via bucky:
 * <ul>
 *   <li>{@code triage/} — the catastrophic-failure flushed open/pending set (the fatal flush always writes and uploads
 *   it);</li>
 *   <li>{@code iss/} — the exact ISS-round block. This is deterministic for this halting ISS: after
 *   {@code awaitFatalShutdown} flushes the block to disk, {@code Hedera.newPlatformStatus(CATASTROPHIC_FAILURE)}
 *   uploads it to {@code iss/} <i>synchronously</i> (before the node halts), so it cannot be lost to a shutdown race.
 *   (The detection path also captures it asynchronously for non-halting ISSes; the two de-duplicate.)</li>
 * </ul>
 */
@Tag(ISS)
class IssHandlingTest implements LifecycleTest {
    private static final Logger log = LogManager.getLogger(IssHandlingTest.class);
    /** In-JVM S3-compatible multipart sink; records the object keys bucky PUTs so the test can verify the uploads. */
    private static HttpServer s3Mock;

    private static int s3Port;
    private static final Set<String> RECEIVED_OBJECT_KEYS = ConcurrentHashMap.newKeySet();

    @BeforeAll
    static void startS3Mock() throws IOException {
        s3Mock = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s3Mock.createContext("/", IssHandlingTest::handleS3Request);
        s3Mock.setExecutor(Executors.newFixedThreadPool(4, runnable -> {
            final Thread thread = new Thread(runnable, "iss-s3-mock");
            thread.setDaemon(true);
            return thread;
        }));
        s3Mock.start();
        s3Port = s3Mock.getAddress().getPort();
        log.info("In-JVM S3 mock listening on 127.0.0.1:{}", s3Port);
    }

    @AfterAll
    static void stopS3Mock() {
        if (s3Mock != null) {
            s3Mock.stop(0);
        }
    }

    @HapiTest
    final Stream<DynamicTest> simulateIss() {
        final AtomicReference<SemanticVersion> startVersion = new AtomicReference<>();
        return hapiTest(
                getVersionInfo().exposingServicesVersionTo(startVersion::set),
                // Wait long enough for node1 to have typically written round 1 snapshot
                // to disk; restarting from this boundary snpshot can surface edge cases
                sleepForSeconds(2),
                // Reconnect node1 with an aberrant ledger.transfers.maxLen override and the failure-upload feature
                // enabled, pointed at the in-JVM S3 mock.
                sourcing(() -> reconnectIssNode(
                        byNodeId(ISS_NODE_ID),
                        configVersionOf(startVersion.get()),
                        // Before restarting node1, update its application properties and write bucket credentials
                        doingContextual(spec -> {
                            final var issNode = spec.getNetworkNodes().get((int) ISS_NODE_ID);
                            final var props = issNode.getExternalPath(APPLICATION_PROPERTIES);
                            final var configDir = issNode.getExternalPath(DATA_CONFIG_DIR);
                            log.info("Configuring ISS node failure-upload + transfer limit @ {}", props);
                            try {
                                // The in-JVM S3 mock does not verify the signature, so any credentials work.
                                Files.writeString(
                                        configDir.resolve("iss-bucket-credentials.properties"),
                                        "accessKey=test\nsecretKey=test\n");
                            } catch (final IOException e) {
                                throw new UncheckedIOException(e);
                            }
                            updateBootstrapProperties(
                                    props,
                                    Map.of(
                                            "ledger.transfers.maxLen", "5",
                                            "failureBlockUpload.issBlockUploadEnabled", "true",
                                            "failureBlockUpload.triageUploadEnabled", "true",
                                            "failureBlockUpload.endpoint", "http://127.0.0.1:" + s3Port + "/",
                                            "failureBlockUpload.bucketName", "iss-debug",
                                            "failureBlockUpload.region", "us-east-1",
                                            "failureBlockUpload.credentialsFileDir",
                                                    configDir.toAbsolutePath().toString(),
                                            "failureBlockUpload.maxRetries", "0"));
                        }))),
                assertHgcaaLogContainsText(
                        NodeSelector.byNodeId(ISS_NODE_ID), "ledger.transfers.maxLen = 5", Duration.ofSeconds(10)),
                // First assert there was no ISS caused by simply reconnecting
                assertHgcaaLogDoesNotContainText(
                        NodeSelector.byNodeId(ISS_NODE_ID), "ISS detected", Duration.ofSeconds(30)),

                // But now submit a transaction within the normal allowed transfers.maxLen limit, while
                // _not_ within the artificial limit set on the reconnected node
                cryptoTransfer(movingHbar(6L).distributing(GENESIS, "3", "4", "5", "6", "7", "8"))
                        .signedBy(GENESIS),
                // Verify we actually got an ISS in node1. Detection lags the offending round by several rounds of
                // state-signature gossip (latency varies), and the ISS node's log is reset when it halts at
                // CATASTROPHIC_FAILURE — so we POLL until the line appears (catching it before any reset) rather than
                // sleep-then-read-once.
                untilHgcaaLogContainsText(
                        NodeSelector.byNodeId(ISS_NODE_ID),
                        "ISS detected",
                        Duration.ofSeconds(180),
                        () -> new SpecOperation[0]),
                // Verify the block stream manager completed its fatal shutdown process
                untilHgcaaLogContainsText(
                        NodeSelector.byNodeId(ISS_NODE_ID),
                        "Block stream fatal shutdown complete",
                        Duration.ofSeconds(60),
                        () -> new SpecOperation[0]),
                // Both the captured ISS block (iss/) and the flushed triage set (triage/) must reach the endpoint.
                untilHgcaaLogContainsText(
                        NodeSelector.byNodeId(ISS_NODE_ID),
                        "Uploaded ISS block file",
                        Duration.ofSeconds(90),
                        () -> new SpecOperation[0]),
                verify(() -> {
                    awaitUploads(Duration.ofSeconds(90));
                    // Match by category only — the object key is {prefix}/{nodeAccount}/{iss|triage}/... and the
                    // network's shard.realm is environment-specific (e.g. 11.12), so don't hard-code the account.
                    assertTrue(
                            receivedKeyContains("/iss/"),
                            "expected an iss/ object uploaded via bucky; saw " + RECEIVED_OBJECT_KEYS);
                    assertTrue(
                            receivedKeyContains("/triage/"),
                            "expected a triage/ object uploaded via bucky; saw " + RECEIVED_OBJECT_KEYS);
                }),
                // Submit a freeze
                freezeOnly().startingIn(2).seconds(),
                waitForFrozenNetwork(FREEZE_TIMEOUT, NodeSelector.exceptNodeIds(ISS_NODE_ID)),
                // And do some more validations
                new ParseableIssBlockStreamValidationOp());
    }

    /** Polls until both an {@code iss/} and a {@code triage/} object have been uploaded, or the timeout elapses. */
    private static void awaitUploads(final Duration timeout) {
        final long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline
                && !(receivedKeyContains("/iss/") && receivedKeyContains("/triage/"))) {
            try {
                Thread.sleep(500);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private static boolean receivedKeyContains(final String substring) {
        return RECEIVED_OBJECT_KEYS.stream().anyMatch(key -> key.contains(substring));
    }

    /** Minimal S3 multipart-upload mock: records each uploaded part's object key (path) and 200s the protocol. */
    private static void handleS3Request(final HttpExchange exchange) throws IOException {
        final String method = exchange.getRequestMethod();
        final String query = exchange.getRequestURI().getRawQuery();
        final String path = exchange.getRequestURI().getPath();
        exchange.getRequestBody().readAllBytes(); // drain
        if ("POST".equals(method) && query != null && query.contains("uploads")) {
            respond(
                    exchange,
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                            + "<InitiateMultipartUploadResult><UploadId>mock-upload-id</UploadId>"
                            + "</InitiateMultipartUploadResult>");
        } else if ("PUT".equals(method) && query != null && query.contains("partNumber")) {
            RECEIVED_OBJECT_KEYS.add(path);
            exchange.getResponseHeaders().set("ETag", "\"mock-etag\"");
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        } else if ("POST".equals(method) && query != null && query.contains("uploadId")) {
            respond(exchange, "<?xml version=\"1.0\" encoding=\"UTF-8\"?><CompleteMultipartUploadResult/>");
        } else {
            exchange.sendResponseHeaders(400, -1);
            exchange.close();
        }
    }

    private static void respond(final HttpExchange exchange, final String xml) throws IOException {
        final byte[] body = xml.getBytes(UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/xml");
        exchange.sendResponseHeaders(200, body.length);
        try (final OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }
}
