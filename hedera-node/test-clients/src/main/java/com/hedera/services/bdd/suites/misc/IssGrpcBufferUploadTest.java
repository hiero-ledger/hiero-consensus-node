// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.misc;

import static com.hedera.services.bdd.junit.TestTags.ISS_GRPC;
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

import com.hedera.services.bdd.HapiBlockNode;
import com.hedera.services.bdd.HapiBlockNode.BlockNodeConfig;
import com.hedera.services.bdd.HapiBlockNode.SubProcessNodeConfig;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.hedera.BlockNodeMode;
import com.hedera.services.bdd.junit.hedera.NodeSelector;
import com.hedera.services.bdd.spec.SpecOperation;
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
 * gRPC-only counterpart to {@link IssHandlingTest}, proving the one piece that test cannot: in
 * {@code blockStream.writerMode=GRPC} nothing is written to disk, so the ISS-round block is captured from the in-memory
 * {@code BlockBufferService} (not the disk resolver) and uploaded to the {@code iss/} bucket folder.
 *
 * <p>Runs on its own fresh gRPC-only network with a block node attached (via {@link HapiBlockNode}); a
 * {@link BlockNodeMode#SIMULATOR} keeps it Docker-free for local runs (flip to {@link BlockNodeMode#REAL} to exercise a
 * real block node). Reuses {@code IssHandlingTest}'s in-JVM S3 mock and {@code failureBlockUpload.*} bucket config
 * verbatim, and the same {@code ledger.transfers.maxLen} ISS induction.
 */
@Tag(ISS_GRPC)
class IssGrpcBufferUploadTest implements LifecycleTest {
    private static final Logger log = LogManager.getLogger(IssGrpcBufferUploadTest.class);

    // Each node is put in gRPC-only mode (writerMode=GRPC) streaming to the block node, so no blocks are written to
    // disk; mirrors BlockNodeBackPressureSuite's GRPC config. failureBlockUpload.* and ledger.transfers.maxLen are set
    // on node1 at runtime (the S3 mock port is dynamic), exactly as IssHandlingTest does.

    /** In-JVM S3-compatible multipart sink; records the object keys bucky PUTs so the test can verify the uploads. */
    private static HttpServer s3Mock;

    private static int s3Port;
    private static final Set<String> RECEIVED_OBJECT_KEYS = ConcurrentHashMap.newKeySet();

    @BeforeAll
    static void startS3Mock() throws IOException {
        s3Mock = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s3Mock.createContext("/", IssGrpcBufferUploadTest::handleS3Request);
        s3Mock.setExecutor(Executors.newFixedThreadPool(4, runnable -> {
            final Thread thread = new Thread(runnable, "iss-grpc-s3-mock");
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
    @HapiBlockNode(
            networkSize = 4,
            blockNodeConfigs = {@BlockNodeConfig(nodeId = 0, mode = BlockNodeMode.SIMULATOR)},
            subProcessNodeConfigs = {
                @SubProcessNodeConfig(
                        nodeId = 0,
                        blockNodeIds = {0},
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {
                            "blockStream.streamMode", "BLOCKS",
                            "blockStream.writerMode", "GRPC",
                            "blockStream.streamWrappedRecordBlocks", "false",
                            "blockStream.enableCutover", "false",
                            "blockStream.buffer.isBufferPersistenceEnabled", "false",
                            "tss.forceMockSignatures", "true"
                        }),
                @SubProcessNodeConfig(
                        nodeId = 1,
                        blockNodeIds = {0},
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {
                            "blockStream.streamMode", "BLOCKS",
                            "blockStream.writerMode", "GRPC",
                            "blockStream.streamWrappedRecordBlocks", "false",
                            "blockStream.enableCutover", "false",
                            "blockStream.buffer.isBufferPersistenceEnabled", "false",
                            "tss.forceMockSignatures", "true"
                        }),
                @SubProcessNodeConfig(
                        nodeId = 2,
                        blockNodeIds = {0},
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {
                            "blockStream.streamMode", "BLOCKS",
                            "blockStream.writerMode", "GRPC",
                            "blockStream.streamWrappedRecordBlocks", "false",
                            "blockStream.enableCutover", "false",
                            "blockStream.buffer.isBufferPersistenceEnabled", "false",
                            "tss.forceMockSignatures", "true"
                        }),
                @SubProcessNodeConfig(
                        nodeId = 3,
                        blockNodeIds = {0},
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {
                            "blockStream.streamMode", "BLOCKS",
                            "blockStream.writerMode", "GRPC",
                            "blockStream.streamWrappedRecordBlocks", "false",
                            "blockStream.enableCutover", "false",
                            "blockStream.buffer.isBufferPersistenceEnabled", "false",
                            "tss.forceMockSignatures", "true"
                        })
            })
    final Stream<DynamicTest> issBlockCapturedFromBufferInGrpcMode() {
        final AtomicReference<SemanticVersion> startVersion = new AtomicReference<>();
        return hapiTest(
                getVersionInfo().exposingServicesVersionTo(startVersion::set),
                // Let node1 write its round-1 snapshot boundary and accumulate a few blocks in the buffer.
                sleepForSeconds(2),
                // Reconnect node1 with the aberrant transfer limit + the failure-upload feature pointed at the S3 mock.
                sourcing(() -> reconnectIssNode(
                        byNodeId(ISS_NODE_ID), configVersionOf(startVersion.get()), doingContextual(spec -> {
                            final var issNode = spec.getNetworkNodes().get((int) ISS_NODE_ID);
                            final var props = issNode.getExternalPath(APPLICATION_PROPERTIES);
                            final var configDir = issNode.getExternalPath(DATA_CONFIG_DIR);
                            log.info("Configuring ISS node failure-upload + transfer limit @ {}", props);
                            try {
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
                // Reconnect alone must not ISS.
                assertHgcaaLogDoesNotContainText(
                        NodeSelector.byNodeId(ISS_NODE_ID), "ISS detected", Duration.ofSeconds(30)),
                // A transfer within the normal limit but above node1's artificial limit → node1 diverges.
                cryptoTransfer(movingHbar(6L).distributing(GENESIS, "3", "4", "5", "6", "7", "8"))
                        .signedBy(GENESIS),
                untilHgcaaLogContainsText(
                        NodeSelector.byNodeId(ISS_NODE_ID),
                        "ISS detected",
                        Duration.ofSeconds(180),
                        () -> new SpecOperation[0]),
                untilHgcaaLogContainsText(
                        NodeSelector.byNodeId(ISS_NODE_ID),
                        "Block stream fatal shutdown complete",
                        Duration.ofSeconds(60),
                        () -> new SpecOperation[0]),
                // The load-bearing proof: only IssBufferBlockReader logs this, so seeing it means the ISS block was
                // sourced from the in-memory buffer (there are no on-disk blocks in gRPC-only mode).
                untilHgcaaLogContainsText(
                        NodeSelector.byNodeId(ISS_NODE_ID),
                        "from buffer block #",
                        Duration.ofSeconds(90),
                        () -> new SpecOperation[0]),
                untilHgcaaLogContainsText(
                        NodeSelector.byNodeId(ISS_NODE_ID),
                        "Uploaded ISS block file",
                        Duration.ofSeconds(90),
                        () -> new SpecOperation[0]),
                verify(() -> {
                    awaitIssUpload(Duration.ofSeconds(90));
                    // In gRPC-only mode an iss/ object can only have been reconstructed from the buffer.
                    assertTrue(
                            receivedKeyContains("/iss/"),
                            "expected an iss/ object uploaded via bucky; saw " + RECEIVED_OBJECT_KEYS);
                }),
                freezeOnly().startingIn(2).seconds(),
                waitForFrozenNetwork(FREEZE_TIMEOUT, NodeSelector.exceptNodeIds(ISS_NODE_ID)));
    }

    /** Polls until an {@code iss/} object has been uploaded, or the timeout elapses. */
    private static void awaitIssUpload(final Duration timeout) {
        final long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline && !receivedKeyContains("/iss/")) {
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
