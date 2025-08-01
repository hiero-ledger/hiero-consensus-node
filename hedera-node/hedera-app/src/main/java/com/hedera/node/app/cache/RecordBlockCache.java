// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.cache;

import static com.hedera.node.app.blocks.impl.streaming.FileBlockItemWriter.COMPLETE_BLOCK_EXTENSION;
import static com.hedera.node.app.blocks.impl.streaming.FileBlockItemWriter.COMPRESSION_ALGORITHM_EXTENSION;
import static com.hedera.node.app.blocks.impl.streaming.FileBlockItemWriter.longToFileName;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.node.app.blocks.impl.BlockStreamManagerImpl;
import com.hedera.node.app.blocks.impl.streaming.BlockState;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.S3IssConfig;
import com.swirlds.common.s3.S3Client;
import com.swirlds.common.s3.S3ClientInitializationException;
import com.swirlds.common.s3.S3ResponseException;
import com.swirlds.state.lifecycle.info.NetworkInfo;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.block.api.BlockItemSet;
import org.hiero.block.api.PublishStreamRequest;
import org.jspecify.annotations.Nullable;

/**
 * An in-memory cache of the last X record stream record files and block stream Block files.
 */
@Singleton
public class RecordBlockCache {

    private static final Logger log = LogManager.getLogger(BlockStreamManagerImpl.class);

    private final BlockStateBuffer blockStreamCache;

    private final ConfigProvider configProvider;
    private final NetworkInfo networkInfo;

    private long issRoundNumber;

    private S3Client s3Client = null;

    @Inject
    public RecordBlockCache(@NonNull ConfigProvider configProvider, @NonNull NetworkInfo networkInfo) {
        // TODO: Use configProvider to set the capacity if needed
        this.blockStreamCache = new BlockStateBuffer(5);
        this.configProvider = configProvider;
        this.networkInfo = networkInfo;
    }

    /**
     * Creates a block state in the BlockState buffer for the given block number.
     * @param blockNumber the block number to create a BlockState for
     */
    public void createBlock(long blockNumber) {
        blockStreamCache.put(blockNumber);
    }

    /**
     * Adds a BlockItem to the BlockState for the given block number if it exists.
     * @param blockNumber the block number to which the BlockItem belongs
     * @param blockItem the BlockItem to add to the BlockState
     */
    public void addBlockItem(long blockNumber, @NonNull BlockItem blockItem) {
        BlockState blockState = blockStreamCache.get(blockNumber);
        if (blockState != null) {
            blockState.addItem(blockItem);
        }
    }

    /**
     * Retrieves the BlockState for a given block number.
     * @param blockNumber the block number to look up
     * @return the BlockState for the block number, or null if not found
     */
    public @Nullable BlockState getBlockState(long blockNumber) {
        return blockStreamCache.get(blockNumber);
    }

    /**
     * Get the BlockState for a given round number, if it exists.
     * @param roundNumber the round number to look up
     * @return the BlockState for the round number, or null if not found
     */
    public BlockState getBlockStateForRoundNumber(long roundNumber) {
        for (final BlockState blockState : blockStreamCache.values()) {
            if (blockState.getLowestRoundNumber() != null) {
                if (roundNumber >= blockState.getLowestRoundNumber()
                        && roundNumber <= blockState.getHighestRoundNumber()) {
                    return blockState;
                }
            }
        }
        return null;
    }

    public void initializeS3Client() {
        S3IssConfig s3IssConfig = configProvider.getConfiguration().getConfigData(S3IssConfig.class);
        if (!s3IssConfig.endpointUrl().isEmpty()) {
            try {
                s3Client = new S3Client(
                        s3IssConfig.regionName(),
                        s3IssConfig.endpointUrl(),
                        s3IssConfig.bucketName(),
                        s3IssConfig.accessKey(),
                        s3IssConfig.secretKey());
            } catch (S3ClientInitializationException e) {
                log.error(
                        "Failed to initialize S3 client for uploading contextual ISS information: {}",
                        e.getMessage(),
                        e);
            }
        }
    }

    /**
     * Uploads the block stream Block for the ISS round number to the S3 bucket.
     */
    public void uploadBlockStreamIssBlock() {
        if (s3Client != null) {
            BlockState blockState = getBlockStateForRoundNumber(issRoundNumber);
            if (blockState != null) {
                uploadBlockStateToS3Bucket(blockState);
            } else {
                log.info("BlockState for ISS round {} is not available, skipping upload to GCP bucket", issRoundNumber);
            }
        } else {
            log.info("Skipping upload of ISS Block to GCP bucket as S3 Client was not initialized properly");
        }
    }

    /**
     * Uploads the ISS context to S3, including the block stream Block and record stream record files.
     */
    public void uploadIssContextToS3() {
        initializeS3Client();
        if (s3Client != null) {
            uploadBlockStreamIssBlock();
            uploadRecordStreamIssRecordFiles();
        }
    }

    private void uploadRecordStreamIssRecordFiles() {}

    private static class BlockStateBuffer extends LinkedHashMap<Long, BlockState> {
        private final int capacity;

        public BlockStateBuffer(int capacity) {
            super(capacity + 1, 0.75f, false); // insertion-order
            this.capacity = capacity;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, BlockState> eldest) {
            return size() > capacity;
        }

        /**
         * Get an existing BlockState by block number, or null if not present.
         */
        public BlockState get(long blockNumber) {
            return super.get(blockNumber);
        }

        /**
         * Add a new BlockState if not already present. Returns true if added.
         */
        public void put(long blockNumber) {
            put(blockNumber, new BlockState(blockNumber));
        }
    }

    /**
     * Uploads the block state to the GCP bucket.
     * @param blockState the block state to upload
     */
    public void uploadBlockStateToS3Bucket(@NonNull final BlockState blockState) {
        requireNonNull(blockState, "blockState must not be null");
        requireNonNull(networkInfo, "networkInfo must not be null");
        List<BlockItem> blockItems = new ArrayList<>();

        // Add all BlockItems from the Requests in the BlockState
        List<PublishStreamRequest> requests = blockState.getRequests();
        for (PublishStreamRequest request : requests) {
            if (request.blockItems() != null) {
                BlockItemSet blockItemSet = request.blockItems();
                blockItems.addAll(blockItemSet.blockItems());
            }
        }
        if (blockState.closedTimestamp() == null) {
            blockItems.addAll(blockState.getPendingItems().stream().toList());
        }

        Block block = Block.newBuilder().items(blockItems).build();

        S3IssConfig s3IssConfig = configProvider.getConfiguration().getConfigData(S3IssConfig.class);
        try (ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                GZIPOutputStream gzipOutputStream = new GZIPOutputStream(byteArrayOutputStream)) {

            // Serialize the Block object into bytes
            byte[] blockBytes = Block.PROTOBUF.toBytes(block).toByteArray();

            // Write the serialized bytes to the GZIPOutputStream
            gzipOutputStream.write(blockBytes);
            gzipOutputStream.close();

            s3Client.uploadFile(
                    s3IssConfig.basePath() + "/node"
                            + networkInfo.selfNodeInfo().nodeId() + "/ISS/"
                            + longToFileName(blockState.blockNumber()) + COMPLETE_BLOCK_EXTENSION
                            + COMPRESSION_ALGORITHM_EXTENSION,
                    s3IssConfig.storageClass(),
                    new ByteArrayIterator(byteArrayOutputStream.toByteArray()),
                    "application/gzip");
            log.info(
                    "Successfully uploaded ISS Block {} to GCP bucket {} at path: {}/ISS/{}",
                    blockState.blockNumber(),
                    s3IssConfig.bucketName(),
                    s3IssConfig.basePath(),
                    longToFileName(blockState.blockNumber()));
        } catch (IOException e) {
            log.info(
                    "Failed to upload Block {} to GCP bucket {}: {}",
                    blockState.blockNumber(),
                    s3IssConfig.bucketName(),
                    e);
        } catch (S3ResponseException e) {
            log.info(
                    "Failed to upload Block {} to GCP bucket {} due to an exceptional response: {}",
                    blockState.blockNumber(),
                    s3IssConfig.bucketName(),
                    e.getMessage(),
                    e);
        }
    }

    private class ByteArrayIterator implements Iterator<byte[]> {

        private byte[] byteArray;
        private boolean hasNext = true;

        ByteArrayIterator(byte[] byteArray) {
            this.byteArray = byteArray;
        }

        @Override
        public boolean hasNext() {
            return hasNext;
        }

        @Override
        public byte[] next() {
            hasNext = false;
            return byteArray;
        }
    }

    /**
     * Sets the ISS round number for which the block state will be uploaded.
     * @param issRoundNumber the ISS round number
     */
    public void setIssRoundNumber(long issRoundNumber) {
        this.issRoundNumber = issRoundNumber;
    }
}
