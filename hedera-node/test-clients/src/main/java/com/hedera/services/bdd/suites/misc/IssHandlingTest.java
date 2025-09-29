// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.misc;

import static com.hedera.services.bdd.junit.SharedNetworkLauncherSessionListener.MINIO_BUCKET_NAME;
import static com.hedera.services.bdd.junit.SharedNetworkLauncherSessionListener.MINIO_ROOT_PASSWORD;
import static com.hedera.services.bdd.junit.SharedNetworkLauncherSessionListener.MINIO_ROOT_PORT;
import static com.hedera.services.bdd.junit.SharedNetworkLauncherSessionListener.MINIO_ROOT_USER;
import static com.hedera.services.bdd.junit.TestTags.ISS;
import static com.hedera.services.bdd.junit.hedera.ExternalPath.APPLICATION_PROPERTIES;
import static com.hedera.services.bdd.junit.hedera.NodeSelector.byNodeId;
import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.updateBootstrapProperties;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getVersionInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertHgcaaLogContains;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertHgcaaLogDoesNotContain;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.freezeOnly;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepForSeconds;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitForFrozenNetwork;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.crypto.ParseableIssBlockStreamValidationOp.ISS_NODE_ID;
import static com.hedera.services.bdd.suites.regression.system.LifecycleTest.configVersionOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.hedera.NodeSelector;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.suites.crypto.ParseableIssBlockStreamValidationOp;
import com.hedera.services.bdd.suites.regression.system.LifecycleTest;
import com.hederahashgraph.api.proto.java.SemanticVersion;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import java.util.zip.GZIPInputStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Validates ISS detection works by reconnecting {@code node1} with an artificially low override for
 * {@code ledger.transfers.maxLen}, then submitting a {@link com.hedera.hapi.node.token.CryptoTransferTransactionBody}
 * that exceeds that artificial limit.
 * <p>
 * This should cause an ISS to be detected in {@code node1}, and the block stream manager to complete its fatal shutdown
 * process. The remaining nodes should still be able to handle transactions and freeze the network.
 */
@Tag(ISS)
class IssHandlingTest implements LifecycleTest {
    private static final Logger log = LogManager.getLogger(IssHandlingTest.class);

    @HapiTest
    final Stream<DynamicTest> simulateIss() {
        final AtomicReference<SemanticVersion> startVersion = new AtomicReference<>();
        final AtomicReference<List<String>> matchingLines = new AtomicReference<>(List.of());
        final AtomicReference<List<String>> successfulPcesUploadingMatchingLines = new AtomicReference<>(List.of());
        final String endpoint = "http://" + HapiSpec.MINIO_CONTAINER.getHost() + ":"
                + HapiSpec.MINIO_CONTAINER.getMappedPort(MINIO_ROOT_PORT);
        return hapiTest(
                getVersionInfo().exposingServicesVersionTo(startVersion::set),
                // Wait long enough for node1 to have typically written round 1 snapshot
                // to disk; restarting from this boundary snpshot can surface edge cases
                sleepForSeconds(2),
                // Reconnect node1 with an aberrant ledger.transfers.maxLen override
                sourcing(() -> reconnectIssNode(
                        byNodeId(ISS_NODE_ID),
                        configVersionOf(startVersion.get()),
                        // Before restarting node0, update its application properties to have a low transfer limit
                        doingContextual(spec -> {
                            final var loc = spec.getNetworkNodes()
                                    .get((int) ISS_NODE_ID)
                                    .getExternalPath(APPLICATION_PROPERTIES);
                            log.info("Setting artificial transfer limit @ {}", loc);
                            updateBootstrapProperties(
                                    loc,
                                    Map.of(
                                            "ledger.transfers.maxLen",
                                            "5",
                                            "s3IssConfig.endpointUrl",
                                            endpoint,
                                            "s3IssConfig.bucketName",
                                            MINIO_BUCKET_NAME,
                                            "s3IssConfig.accessKey",
                                            MINIO_ROOT_USER,
                                            "s3IssConfig.secretKey",
                                            MINIO_ROOT_PASSWORD,
                                            "s3IssConfig.enabled",
                                            "true"));
                        }))),
                assertHgcaaLogContains(
                        NodeSelector.byNodeId(ISS_NODE_ID), "ledger.transfers.maxLen = 5", Duration.ofSeconds(10)),
                // First assert there was no ISS caused by simply reconnecting
                assertHgcaaLogDoesNotContain(
                        NodeSelector.byNodeId(ISS_NODE_ID), "ISS detected", Duration.ofSeconds(30)),

                // But now submit a transaction within the normal allowed transfers.maxLen limit, while
                // _not_ within the artificial limit set on the reconnected node
                cryptoTransfer(movingHbar(6L).distributing(GENESIS, "3", "4", "5", "6", "7", "8"))
                        .signedBy(GENESIS)
                        .via("issTransfer"),
                // Verify we actually got an ISS in node1
                assertHgcaaLogContains(NodeSelector.byNodeId(ISS_NODE_ID), "ISS detected", Duration.ofSeconds(60)),
                // Verify the block stream manager completed its fatal shutdown process
                assertHgcaaLogContains(
                        NodeSelector.byNodeId(ISS_NODE_ID),
                        "Block stream fatal shutdown complete",
                        Duration.ofSeconds(30)),
                // Verify the ISS Record Stream and Block Stream block files were written to S3 bucket
                assertHgcaaLogContains(
                                NodeSelector.byNodeId(ISS_NODE_ID),
                                "Successfully uploaded ISS Block",
                                Duration.ofSeconds(30))
                        .exposingLines(matchingLines),
                assertHgcaaLogContains(
                                NodeSelector.byNodeId(ISS_NODE_ID),
                                "Successfully uploaded ISS Pces file",
                                Duration.ofSeconds(30))
                        .exposingLines(successfulPcesUploadingMatchingLines),
                assertHgcaaLogContains(
                        NodeSelector.byNodeId(ISS_NODE_ID),
                        "Successfully uploaded ISS Record Stream file",
                        Duration.ofSeconds(30)),
                doingContextual(spec -> verifyIssTransactionBytesInS3BucketISSBlocks(
                        matchingLines, endpoint, spec.registry().getBytes("issTransfer"))),
                doingContextual(spec -> verifyIssPcesFilesInS3Bucket(successfulPcesUploadingMatchingLines, endpoint)),
                // Submit a freeze
                freezeOnly().startingIn(2).seconds(),
                waitForFrozenNetwork(FREEZE_TIMEOUT, NodeSelector.exceptNodeIds(ISS_NODE_ID)),
                // And do some more validations
                new ParseableIssBlockStreamValidationOp());
    }

    private void verifyIssPcesFilesInS3Bucket(
            final AtomicReference<List<String>> matchingLines, final String endpoint) {
        if (!matchingLines.get().isEmpty()) {
            try {
                MinioClient minioClient = MinioClient.builder()
                        .endpoint(endpoint)
                        .credentials(MINIO_ROOT_USER, MINIO_ROOT_PASSWORD)
                        .build();
                final Set<String> allObjects = getAllObjects(minioClient);

                // Extract the path from the log line "Successfully uploaded ISS Pces file {} to S3 at path: {}"
                matchingLines.get().stream()
                        .map(s -> s.split("path: ")[1].trim())
                        .forEach(key -> assertTrue(allObjects.contains(key)));
            } catch (Exception e) {
                throw new RuntimeException("Failed to verify ISS Pces files are present in S3 bucket", e);
            }
        }
    }

    private void verifyIssTransactionBytesInS3BucketISSBlocks(
            AtomicReference<List<String>> matchingLines, String endpoint, byte[] issCryptoTransferBytes) {
        if (!matchingLines.get().isEmpty()) {
            try {
                MinioClient minioClient = MinioClient.builder()
                        .endpoint(endpoint)
                        .credentials(MINIO_ROOT_USER, MINIO_ROOT_PASSWORD)
                        .build();
                // Extract the path from the log line "Successfully uploaded ISS Block {} (Rounds {}-{}) to GCP bucket
                // {} at path: {}"
                final String blockPath =
                        matchingLines.get().getFirst().split("path: ")[1].trim();
                log.info("Verifying ISS Block file exists at path: {}", blockPath);
                // Verify the ISS Block file exists in the S3 bucket

                final Set<String> allObjects = getAllObjects(minioClient);
                allObjects.forEach(System.out::println);
                assertTrue(allObjects.contains(blockPath));

                GetObjectResponse response = minioClient.getObject(GetObjectArgs.builder()
                        .bucket(MINIO_BUCKET_NAME)
                        .object(blockPath)
                        .build());
                byte[] storedObject = response.readAllBytes();
                // First Uncompress the gzipped content
                ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(storedObject);
                GZIPInputStream gzipInputStream = new GZIPInputStream(byteArrayInputStream);
                byte[] decompressedData = gzipInputStream.readAllBytes();
                gzipInputStream.close();
                byteArrayInputStream.close();
                // Then parse the Block from the decompressed data
                Block storedBlock = Block.PROTOBUF.parse(Bytes.wrap(decompressedData));
                Transaction issTransaction = Transaction.PROTOBUF.parse(Bytes.wrap(issCryptoTransferBytes));

                log.info("Verifying ISS SignedTransaction Bytes are present in the Block Stream ISS Block file...");

                // Verify the ISS Transaction Bytes are present in the ISS Block file
                for (BlockItem item : storedBlock.items()) {
                    if (item.hasSignedTransaction()) {
                        Bytes signedTransaction = item.signedTransaction();
                        if (Arrays.equals(
                                signedTransaction.toByteArray(),
                                issTransaction.signedTransactionBytes().toByteArray())) {
                            log.info("ISS SignedTransaction Bytes found in ISS Block file at path: {}", blockPath);
                            return; // Found the transaction, exit the loop
                        }
                    }
                }
                // TODO verify TransactionResult
                log.error("ISS SignedTransaction Bytes not found in ISS Block file at path: {}", blockPath);
                throw new RuntimeException(
                        "ISS SignedTransaction Bytes not found in ISS Block Stream Block file in S3 bucket");
            } catch (Exception e) {
                log.error("Error verifying ISS Block file in S3 bucket: {}", e.getMessage());
                throw new RuntimeException(
                        "Failed to verify ISS Transaction Bytes present in ISS Block files in S3 bucket", e);
            }
        }
    }

    /**
     * Get all the objects in the bucket.
     *
     * @return Set of object names, aka full path
     */
    private Set<String> getAllObjects(@NonNull final MinioClient minioClient) {
        try {
            return StreamSupport.stream(
                            minioClient
                                    .listObjects(ListObjectsArgs.builder()
                                            .bucket(MINIO_BUCKET_NAME)
                                            .recursive(true)
                                            .build())
                                    .spliterator(),
                            false)
                    .map(result -> {
                        try {
                            return result.get().objectName();
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .collect(Collectors.toSet());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
