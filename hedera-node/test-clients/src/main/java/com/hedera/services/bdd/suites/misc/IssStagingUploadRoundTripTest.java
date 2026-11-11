// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.misc;

import static com.hedera.services.bdd.junit.TestTags.ISS_GRPC;
import static com.hedera.services.bdd.junit.hedera.NodeSelector.byNodeId;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getVersionInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.utilops.BlockNodeVerbs.blockNode;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertHgcaaLogContainsText;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertHgcaaLogDoesNotContainText;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.freezeOnly;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepForSeconds;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.untilHgcaaLogContainsText;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitForFrozenNetwork;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.crypto.ParseableIssBlockStreamValidationOp.ISS_NODE_ID;
import static com.hedera.services.bdd.suites.regression.system.LifecycleTest.configVersionOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

import com.hedera.hapi.block.stream.Block;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.HapiBlockNode;
import com.hedera.services.bdd.HapiBlockNode.BlockNodeConfig;
import com.hedera.services.bdd.HapiBlockNode.SubProcessNodeConfig;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.junit.hedera.BlockNodeMode;
import com.hedera.services.bdd.junit.hedera.NodeSelector;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.suites.regression.system.LifecycleTest;
import com.hederahashgraph.api.proto.java.SemanticVersion;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * Round trip with the real deployment uploader: the ISS node stages its ISS-round block and triage set with upload
 * markers, and an {@code uploader-mirror} container (mirror.py in marker mode, set up the way DevOps must run it for
 * {@code issBlockDir}) uploads them to an in-JVM S3 mock and then deletes them locally. Needs Docker.
 */
@Tag(ISS_GRPC)
@OrderedInIsolation
class IssStagingUploadRoundTripTest implements LifecycleTest {
    /** Outside our control: the uploader image DevOps runs. */
    private static final String UPLOADER_IMAGE = "gcr.io/hedera-registry/uploader-mirror:4.1.0";
    /** Not DNS-safe on purpose: boto3 then sends path-style requests, which the in-JVM mock can serve. */
    private static final String BUCKET = "iss_blocks";
    /** One-level mount path, so the uploader's {@code BUCKET_PATH=iss/*} keeps the incident folders in object keys. */
    private static final String MOUNT = "/issBlocks";

    private static S3Mock s3;
    private static GenericContainer<?> uploader;

    private final AtomicReference<Path> issBlockDir = new AtomicReference<>();

    @AfterAll
    static void stopUploader() {
        if (uploader != null) {
            uploader.stop();
        }
        if (s3 != null) {
            s3.close();
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
                            "blockStream.buffer.maxBlocks", "200",
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
                            "blockStream.buffer.maxBlocks", "200",
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
                            "blockStream.buffer.maxBlocks", "200",
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
                            "blockStream.buffer.maxBlocks", "200",
                            "tss.forceMockSignatures", "true"
                        })
            })
    final Stream<DynamicTest> stagedIssBlocksAreUploadedThenDeleted() {
        final AtomicReference<SemanticVersion> startVersion = new AtomicReference<>();
        return hapiTest(
                getVersionInfo().exposingServicesVersionTo(startVersion::set),
                sleepForSeconds(2),
                sourcing(() -> reconnectIssNode(
                        byNodeId(ISS_NODE_ID),
                        configVersionOf(startVersion.get()),
                        IssStagingTestSupport.configureFailureStaging(issBlockDir))),
                assertHgcaaLogContainsText(
                        byNodeId(ISS_NODE_ID), "ledger.transfers.maxLen = 5", Duration.ofSeconds(10)),
                // Reconnect alone must not ISS.
                assertHgcaaLogDoesNotContainText(byNodeId(ISS_NODE_ID), "ISS detected", Duration.ofSeconds(30)),
                // As in a deployment, the uploader is already watching the staging dir when the ISS hits.
                doingContextual(spec -> startUploader(issBlockDir.get())),
                // Withhold acks so the ISS-round block stays buffered through the detection lag.
                blockNode(0).updateSendingBlockAcknowledgements(false),
                cryptoTransfer(movingHbar(6L).distributing(GENESIS, "3", "4", "5", "6", "7", "8"))
                        .signedBy(GENESIS),
                untilHgcaaLogContainsText(
                        byNodeId(ISS_NODE_ID), "ISS detected", Duration.ofSeconds(180), () -> new SpecOperation[0]),
                untilHgcaaLogContainsText(
                        byNodeId(ISS_NODE_ID),
                        "Block stream fatal shutdown complete",
                        Duration.ofSeconds(60),
                        () -> new SpecOperation[0]),
                // Only IssBufferBlockReader logs this, so the ISS block came from the in-memory buffer.
                untilHgcaaLogContainsText(
                        byNodeId(ISS_NODE_ID),
                        "from buffer block #",
                        Duration.ofSeconds(90),
                        () -> new SpecOperation[0]),
                assertHgcaaLogContainsText(byNodeId(ISS_NODE_ID), "wrote 1 block(s)", Duration.ofSeconds(90)),
                untilHgcaaLogContainsText(
                        byNodeId(ISS_NODE_ID), "Staged ISS round", Duration.ofSeconds(90), () -> new SpecOperation[0]),
                untilHgcaaLogContainsText(
                        byNodeId(ISS_NODE_ID),
                        "Triage block staging complete",
                        Duration.ofSeconds(90),
                        () -> new SpecOperation[0]),
                doingContextual(spec -> awaitUploadedAndDeleted(issBlockDir.get(), Duration.ofSeconds(90))),
                blockNode(0).updateSendingBlockAcknowledgements(true),
                freezeOnly().startingIn(2).seconds(),
                waitForFrozenNetwork(FREEZE_TIMEOUT, NodeSelector.exceptNodeIds(ISS_NODE_ID)));
    }

    /** Starts the S3 mock and an uploader container on {@code issBlockDir}, with the settings DevOps must use. */
    private static void startUploader(final Path issBlockDir) {
        try {
            // Must exist before the bind mount, or Docker creates it owned by root and the node cannot write to it.
            Files.createDirectories(issBlockDir);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        s3 = new S3Mock();
        Testcontainers.exposeHostPorts(s3.port());
        uploader = new GenericContainer<>(DockerImageName.parse(UPLOADER_IMAGE))
                .withFileSystemBind(issBlockDir.toString(), MOUNT, BindMode.READ_WRITE)
                .withEnv("S3_ENABLE", "true")
                .withEnv("GCS_ENABLE", "false")
                .withEnv("SIG_REQUIRE", "true")
                .withEnv("SIG_EXTENSION", "mf")
                .withEnv("STREAM_EXTENSION", "gz")
                .withEnv("REAPER_ENABLE", "true")
                .withEnv("REAPER_INTERVAL", "5")
                .withEnv("DIR_PATTERN", "block-*/*/*")
                .withEnv("BUCKET_NAME", BUCKET)
                .withEnv("BUCKET_PATH", "iss/*")
                .withEnv("S3_ACCESS_KEY", "test")
                .withEnv("S3_SECRET_KEY", "test")
                .withCommand(
                        "python",
                        "application.py",
                        "--linux",
                        "--is-block-stream",
                        "--watch-directory",
                        MOUNT,
                        "--s3-endpoint",
                        "http://host.testcontainers.internal:" + s3.port(),
                        "--debug")
                .waitingFor(Wait.forLogMessage(".*Mirror .* Starting \\[ watch_directory.*", 1)
                        .withStartupTimeout(Duration.ofMinutes(2)));
        uploader.start();
    }

    /** Waits until the ISS-round block and the triage set are in the bucket and no file is left on local disk. */
    private static void awaitUploadedAndDeleted(final Path issBlockDir, final Duration timeout) {
        final String prefix = "/" + BUCKET + "/iss/block-[^/]+/[^/]+/";
        final long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            final List<String> keys = List.copyOf(s3.objects().keySet());
            final List<String> left = IssStagingTestSupport.stagedRegularFiles(issBlockDir);
            final String issKey = keys.stream()
                    .filter(k -> k.matches(prefix + "(detect|failure)/[^/]+\\.iss\\.gz"))
                    .findFirst()
                    .orElse(null);
            final boolean triageUploaded = keys.stream().anyMatch(k -> k.matches(prefix + "triage/[^/]+\\.gz"));
            if (issKey != null && triageUploaded && left.isEmpty()) {
                assertIsBlock(s3.objects().get(issKey));
                return;
            }
            if (System.nanoTime() > deadline) {
                fail("Staged files were not all uploaded and deleted; uploaded " + keys + ", still on disk " + left
                        + "\nuploader log:\n" + uploader.getLogs());
            }
            try {
                Thread.sleep(1_000L);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("Interrupted while waiting for the uploader");
            }
        }
    }

    private static void assertIsBlock(final byte[] gzipped) {
        try (final var in = new GZIPInputStream(new ByteArrayInputStream(gzipped))) {
            final Block block = Block.PROTOBUF.parse(Bytes.wrap(in.readAllBytes()));
            assertFalse(block.items().isEmpty(), "the uploaded ISS-round block has no items");
        } catch (final IOException | ParseException e) {
            fail("the uploaded ISS-round block is not a gzipped Block", e);
        }
    }

    /** In-JVM S3 mock: PUT stores an object; HEAD returns its MD5 ETag, which is what mirror.py checks after upload. */
    private static final class S3Mock implements AutoCloseable {
        private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
        private final HttpServer server;

        S3Mock() {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
            server.createContext("/", exchange -> {
                final String path = exchange.getRequestURI().getPath();
                switch (exchange.getRequestMethod()) {
                    case "PUT" -> {
                        final byte[] data = exchange.getRequestBody().readAllBytes();
                        objects.put(path, data);
                        exchange.getResponseHeaders().set("ETag", etag(data));
                        exchange.sendResponseHeaders(200, -1);
                    }
                    case "HEAD" -> {
                        final byte[] data = objects.get(path);
                        if (data != null) {
                            exchange.getResponseHeaders().set("ETag", etag(data));
                        }
                        exchange.sendResponseHeaders(data != null ? 200 : 404, -1);
                    }
                    default -> exchange.sendResponseHeaders(400, -1);
                }
                exchange.close();
            });
            server.start();
        }

        int port() {
            return server.getAddress().getPort();
        }

        Map<String, byte[]> objects() {
            return objects;
        }

        @Override
        public void close() {
            server.stop(0);
        }

        private static String etag(final byte[] data) {
            try {
                return '"'
                        + HexFormat.of()
                                .formatHex(MessageDigest.getInstance("MD5").digest(data))
                        + '"';
            } catch (final NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
