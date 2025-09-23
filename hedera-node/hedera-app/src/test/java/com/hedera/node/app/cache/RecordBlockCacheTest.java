// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.input.RoundHeader;
import com.hedera.hapi.block.stream.output.BlockHeader;
import com.hedera.node.app.records.impl.producers.formats.BlockRecordWriterFactoryImpl;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.node.config.data.S3Config;
import com.hedera.node.config.data.S3IssConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.platform.test.fixtures.simulated.RandomSigner;
import com.swirlds.state.lifecycle.info.NetworkInfo;
import com.swirlds.state.lifecycle.info.NodeInfo;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import java.io.ByteArrayInputStream;
import java.nio.file.FileSystem;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.testcontainers.containers.GenericContainer;

@ExtendWith(MockitoExtension.class)
public class RecordBlockCacheTest {

    @Mock
    private ConfigProvider configProvider;

    @Mock
    private NetworkInfo networkInfo;

    private static final String BUCKET_NAME = "test-bucket";
    private static final int MINIO_ROOT_PORT = 9000;
    private static final String MINIO_ROOT_USER = "minioadmin";
    private static final String MINIO_ROOT_PASSWORD = "minioadmin";
    private MinioClient minioClient;

    @Test
    void testUploadBlockStreamBlock() throws Exception {
        NodeInfo mockSelfNodeInfo = mock(NodeInfo.class);
        given(networkInfo.selfNodeInfo()).willReturn(mockSelfNodeInfo);
        given(mockSelfNodeInfo.nodeId()).willReturn(3L);

        // Start MinIO container
        GenericContainer<?> minioContainer = new GenericContainer<>("minio/minio:latest")
                .withCommand("server /data")
                .withExposedPorts(MINIO_ROOT_PORT)
                .withEnv("MINIO_ROOT_USER", MINIO_ROOT_USER)
                .withEnv("MINIO_ROOT_PASSWORD", MINIO_ROOT_PASSWORD);
        minioContainer.start();
        // Initialize MinIO client
        String endpoint = "http://" + minioContainer.getHost() + ":" + minioContainer.getMappedPort(MINIO_ROOT_PORT);
        minioClient = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(MINIO_ROOT_USER, MINIO_ROOT_PASSWORD)
                .build();
        // Create a bucket
        minioClient.makeBucket(MakeBucketArgs.builder().bucket(BUCKET_NAME).build());

        final var config = HederaTestConfigBuilder.create()
                .withConfigDataType(BlockStreamConfig.class)
                .withValue("blockStream.blockPeriod", Duration.of(2, ChronoUnit.SECONDS))
                .withValue("s3Config.enabled", "true")
                .withValue("s3Config.regionName", "us-central1")
                .withValue("s3Config.bucketName", BUCKET_NAME)
                .withValue("s3Config.endpointUrl", endpoint)
                .withValue("s3Config.accessKey", MINIO_ROOT_USER)
                .withValue("s3Config.secretKey", MINIO_ROOT_PASSWORD)
                .withValue("s3IssConfig.basePath", "blocks")
                .withValue("s3Config.storageClass", "STANDARD")
                .withConfigDataType(S3Config.class)
                .withConfigDataType(S3IssConfig.class)
                .getOrCreateConfig();
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(config, 1L));

        RecordBlockCache recordBlockCache = new RecordBlockCache(
                configProvider,
                networkInfo,
                new BlockRecordWriterFactoryImpl(
                        configProvider, mockSelfNodeInfo, new RandomSigner(new Random()), mock(FileSystem.class)));

        BlockItem blockItem = BlockItem.newBuilder()
                .blockHeader(BlockHeader.newBuilder().number(42).build())
                .build();

        recordBlockCache.createBlock(42);
        recordBlockCache.addBlockItem(42, blockItem);
        recordBlockCache.addBlockItem(
                42,
                BlockItem.newBuilder()
                        .roundHeader(RoundHeader.newBuilder().roundNumber(53).build())
                        .build());
        recordBlockCache.setIssRoundNumber(53);
        final S3Uploader s3Uploader = new S3Uploader(config.getConfigData(S3Config.class));
        recordBlockCache.uploadIssContextToS3(s3Uploader);

        final Set<String> allObjects = getAllObjects();
        allObjects.forEach(System.out::println);
        assertTrue(allObjects.contains("blocks/node3/ISS/000000000000000000000000000000000042.blk.gz"));

        GetObjectResponse response = minioClient.getObject(GetObjectArgs.builder()
                .bucket(BUCKET_NAME)
                .object("blocks/node3/ISS/000000000000000000000000000000000042.blk.gz")
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
        // Assert the block matches
        assertEquals(storedBlock.items().getFirst().blockHeader().number(), 42L);
    }

    /**
     * Get all the objects in the bucket.
     *
     * @return Set of object names, aka full path
     */
    private Set<String> getAllObjects() {
        try {
            return StreamSupport.stream(
                            minioClient
                                    .listObjects(ListObjectsArgs.builder()
                                            .bucket(BUCKET_NAME)
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
