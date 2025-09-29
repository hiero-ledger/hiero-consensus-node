// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.cache;

import static com.hedera.node.app.blocks.impl.streaming.FileBlockItemWriter.COMPLETE_BLOCK_EXTENSION;
import static com.hedera.node.app.blocks.impl.streaming.FileBlockItemWriter.COMPRESSION_ALGORITHM_EXTENSION;
import static com.hedera.node.app.blocks.impl.streaming.FileBlockItemWriter.longToFileName;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.output.SingletonUpdateChange;
import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.block.stream.output.StateChanges;
import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.platform.state.MinimumJudgeInfo;
import com.hedera.hapi.streams.HashObject;
import com.hedera.node.app.blocks.impl.streaming.BlockState;
import com.hedera.node.app.records.impl.producers.BlockRecordWriter;
import com.hedera.node.app.records.impl.producers.BlockRecordWriterFactory;
import com.hedera.node.app.records.impl.producers.SerializedSingleTransactionRecord;
import com.hedera.node.app.spi.info.NetworkInfo;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.node.config.data.S3IssConfig;
import com.hedera.node.config.types.StreamMode;
import com.swirlds.platform.state.SavedStateUtils;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.block.api.BlockItemSet;
import org.hiero.block.api.PublishStreamRequest;
import org.hiero.consensus.model.node.NodeId;

/**
 * An in-memory cache of the last X record stream record files and block stream block files.
 * This class also handles the uploading of ISS contextual blocks and record files to an S3 bucket.
 */
@Singleton
public class RecordBlockCache {

    private static final Logger log = LogManager.getLogger(RecordBlockCache.class);

    private final StreamBuffer<BlockState> blockStreamCache;
    private final StreamBuffer<RecordStreamMetadata> recordStreamCache;

    private final ConfigProvider configProvider;
    private final NetworkInfo networkInfo;

    private long issRoundNumber;

    private S3IssUploader s3IssUploader;

    private final BlockRecordWriterFactory blockRecordWriterFactory;

    /**
     * Constructor for RecordBlockCache.
     * @param configProvider the configuration provider to access S3 ISS configuration
     * @param networkInfo the network information to access self node info
     * @param blockRecordWriterFactory the factory to create BlockRecordWriter instances
     */
    @Inject
    public RecordBlockCache(
            @NonNull ConfigProvider configProvider,
            @NonNull NetworkInfo networkInfo,
            @NonNull BlockRecordWriterFactory blockRecordWriterFactory) {
        final int capacity = configProvider
                .getConfiguration()
                .getConfigData(S3IssConfig.class)
                .recordBlockBufferSize();
        this.blockStreamCache = new StreamBuffer<>(capacity);
        this.recordStreamCache = new StreamBuffer<>(capacity);
        this.configProvider = configProvider;
        this.networkInfo = networkInfo;
        this.blockRecordWriterFactory = blockRecordWriterFactory;
    }

    /**
     * Creates a block state in the BlockState buffer for the given block number.
     * @param blockNumber the block number to create a BlockState for
     */
    public void createBlock(long blockNumber) {
        blockStreamCache.put(blockNumber, new BlockState(blockNumber));
    }

    /**
     * Adds a BlockItem to the BlockState for the given block number if it exists.
     * @param blockNumber the block number to which the BlockItem belongs
     * @param blockItem the BlockItem to add to the BlockState
     */
    public void addBlockItem(long blockNumber, @NonNull BlockItem blockItem) {
        final BlockState blockState = blockStreamCache.get(blockNumber);
        if (blockState != null) {
            blockState.addItem(blockItem);
        }
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

    /**
     * Initializes the S3 client for uploading ISS Blocks to the S3 bucket.
     */
    public void initializeUploader() {
        final S3IssConfig s3Config = configProvider.getConfiguration().getConfigData(S3IssConfig.class);
        if (s3Config.enabled() && !s3Config.endpointUrl().isEmpty()) {
            try {
                this.s3IssUploader = new S3IssUploader(s3Config);
            } catch (IllegalStateException e) {
                log.error("Failed to initialize S3 client for uploading contextual ISS Blocks: {}", e.getMessage(), e);
            }
        }
    }

    /**
     * Uploads the block stream Block for the ISS round number to the S3 bucket and writes it to local disk.
     */
    public void handleBlockStreamIssBlock() {
        if (s3IssUploader != null) {
            BlockState blockState = getBlockStateForRoundNumber(issRoundNumber);
            if (blockState != null) {
                uploadBlockStateToS3BucketAndWriteToDisk(blockState);
            } else {
                log.info(
                        "BlockState for round {} in which ISS was reported is not available, skipping upload to S3 bucket",
                        issRoundNumber);
            }
        } else {
            log.info("Skipping upload of ISS Block to GCP bucket as S3 Client was not initialized properly");
        }
    }

    /**
     * Uploads the ISS context to S3, including the block stream Block and record stream record files.
     */
    public void uploadIssContextToS3() {
        initializeUploader();
        if (s3IssUploader != null) {
            StreamMode streamMode = configProvider
                    .getConfiguration()
                    .getConfigData(BlockStreamConfig.class)
                    .streamMode();
            if (streamMode == StreamMode.BOTH || streamMode == StreamMode.BLOCKS) {
                handleBlockStreamIssBlock();
            }
            if (streamMode == StreamMode.BOTH || streamMode == StreamMode.RECORDS) {
                handleRecordStreamIssRecordFiles();
            }
        }
    }

    private void handleRecordStreamIssRecordFiles() {
        if (s3IssUploader != null) {
            for (final RecordStreamMetadata recordFileMetadata : recordStreamCache.values()) {
                uploadRecordFileToS3(recordFileMetadata);
            }
        }
    }

    private void uploadRecordFileToS3(RecordStreamMetadata recordFileMetadata) {
        List<SerializedSingleTransactionRecord> serializedSingleTransactionRecords =
                recordFileMetadata.getRecordItems();
        if (serializedSingleTransactionRecords.isEmpty()) {
            log.info(
                    "No SerializedSingleTransactionRecord's found for Block number {}. Skipping upload to S3 bucket.",
                    recordFileMetadata.getBlockNumber());
            return;
        }

        BlockRecordWriter blockRecordWriter = blockRecordWriterFactory.create(
                configProvider
                                .getConfiguration()
                                .getConfigData(S3IssConfig.class)
                                .diskPath() + "/" + issRoundNumber + "/");
        blockRecordWriter.init(
                recordFileMetadata.getHapiProtoVersion(),
                recordFileMetadata.getStartRunningHash(),
                recordFileMetadata.getConsensusTime(),
                recordFileMetadata.getBlockNumber());
        for (SerializedSingleTransactionRecord rec : serializedSingleTransactionRecords) {
            blockRecordWriter.writeItem(rec);
        }
        blockRecordWriter.close(recordFileMetadata.getLastRunningHash());

        // Upload all the record block files in S3IssConfig::diskPath
        final S3IssConfig s3IssConfig = configProvider.getConfiguration().getConfigData(S3IssConfig.class);
        final Path issRecordFilePath = Paths.get(s3IssConfig.diskPath());
        try (Stream<Path> stream = Files.walk(issRecordFilePath)) {
            stream.filter(Files::isRegularFile).forEach(path -> {
                final Path fileName = path.getFileName();
                if (fileName != null) {
                    try {
                        final String fileKey = s3IssConfig.basePath() + "/ISS" + "/node"
                                + networkInfo.selfNodeInfo().nodeId() + "/" + issRoundNumber + "/"
                                + fileName;
                        // Upload the file to S3 bucket
                        s3IssUploader.uploadFile(fileKey, path, "application/gzip");
                        log.info(
                                "Successfully uploaded ISS Record Stream file {} for Block number {} to S3 bucket {} at path: {}",
                                fileName,
                                recordFileMetadata.getBlockNumber(),
                                s3IssConfig.bucketName(),
                                fileKey);
                    } catch (Exception e) {
                        log.error(
                                "Failed to upload Record Stream files for Block number {} to S3 bucket {} due to: {}",
                                recordFileMetadata.getBlockNumber(),
                                s3IssConfig.bucketName(),
                                e.getMessage(),
                                e);
                    }
                }
            });
        } catch (IOException e) {
            log.error(
                    "Failed to read Record Stream files from disk path {} due to: {}",
                    issRecordFilePath,
                    e.getMessage(),
                    e);
        }
    }

    /**
     * Adds the SerializedSingleTransactionRecord item to the RecordStreamMetadata for the given block number if it exists.
     * @param blockNumber the block number
     * @param item the SerializedSingleTransactionRecord item to add
     */
    public void addRecordStreamItem(long blockNumber, SerializedSingleTransactionRecord item) {
        RecordStreamMetadata recordStreamMetadata = recordStreamCache.get(blockNumber);
        if (recordStreamMetadata != null) {
            recordStreamMetadata.addRecordItem(item);
        }
    }

    /**
     * Closes the record stream file for the given block number by setting the last running hash.
     * @param blockNumber the block number for which to close the record stream file
     * @param lastRunningHash the last running hash to set for the record stream file
     */
    public void closeRecordStreamFile(long blockNumber, HashObject lastRunningHash) {
        RecordStreamMetadata recordStreamMetadata = recordStreamCache.get(blockNumber);
        if (recordStreamMetadata != null) {
            recordStreamMetadata.setLastRunningHash(lastRunningHash);
        }
    }

    public void createRecordStreamBlock(
            SemanticVersion hapiVersion, HashObject startRunningHash, Instant startConsensusTime, long blockNumber) {
        requireNonNull(hapiVersion, "hapiVersion must not be null");
        requireNonNull(startRunningHash, "startRunningHash must not be null");
        requireNonNull(startConsensusTime, "startConsensusTime must not be null");

        final RecordStreamMetadata recordStreamMetadata = new RecordStreamMetadata(blockNumber);
        recordStreamMetadata.setStartRunningHash(startRunningHash);
        recordStreamMetadata.setConsensusTime(startConsensusTime);
        recordStreamMetadata.setHapiProtoVersion(hapiVersion);
        recordStreamCache.put(blockNumber, recordStreamMetadata);
    }

    private static class RecordStreamMetadata {
        private final long blockNumber;
        private Instant consensusTime;

        private HashObject startRunningHash;
        private SemanticVersion hapiProtoVersion;
        private final List<SerializedSingleTransactionRecord> recordItems = new ArrayList<>();

        private HashObject lastRunningHash;

        public RecordStreamMetadata(final long blockNumber) {
            this.blockNumber = blockNumber;
        }

        public long getBlockNumber() {
            return blockNumber;
        }

        public void setConsensusTime(Instant consensusTime) {
            this.consensusTime = consensusTime;
        }

        public Instant getConsensusTime() {
            return consensusTime;
        }

        public void addRecordItem(SerializedSingleTransactionRecord item) {
            recordItems.add(item);
        }

        public List<SerializedSingleTransactionRecord> getRecordItems() {
            return recordItems;
        }

        public void setLastRunningHash(HashObject lastRunningHash) {
            this.lastRunningHash = lastRunningHash;
        }

        public HashObject getStartRunningHash() {
            return startRunningHash;
        }

        public void setStartRunningHash(HashObject startRunningHash) {
            this.startRunningHash = startRunningHash;
        }

        public void setHapiProtoVersion(SemanticVersion hapiVersion) {
            this.hapiProtoVersion = hapiVersion;
        }

        public SemanticVersion getHapiProtoVersion() {
            return hapiProtoVersion;
        }

        public HashObject getLastRunningHash() {
            return lastRunningHash;
        }
    }

    private static class StreamBuffer<T> extends LinkedHashMap<Long, T> {
        private final int capacity;

        public StreamBuffer(int capacity) {
            super(capacity + 1, 0.75f, false); // insertion-order
            this.capacity = capacity;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, T> eldest) {
            return size() > capacity;
        }

        /**
         * Get an existing item by block number, or null if not present.
         */
        public T get(long blockNumber) {
            return super.get(blockNumber);
        }
    }

    /**
     * Uploads the block state to the GCP bucket.
     * @param blockState the block state to upload
     */
    public void uploadBlockStateToS3BucketAndWriteToDisk(@NonNull final BlockState blockState) {
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

        final Block block = Block.newBuilder().items(blockItems).build();

        S3IssConfig s3IssConfig = configProvider.getConfiguration().getConfigData(S3IssConfig.class);
        try (ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                GZIPOutputStream gzipOutputStream = new GZIPOutputStream(byteArrayOutputStream)) {

            // Serialize the Block object into bytes
            byte[] blockBytes = Block.PROTOBUF.toBytes(block).toByteArray();

            // Write the serialized bytes to the GZIPOutputStream
            gzipOutputStream.write(blockBytes);
            gzipOutputStream.close();

            // Also write Block File to local disk
            Path issBlockFilePath = Paths.get(s3IssConfig.diskPath())
                    .resolve(issRoundNumber
                            + "/blockStream" + longToFileName(blockState.blockNumber()) + COMPLETE_BLOCK_EXTENSION
                            + COMPRESSION_ALGORITHM_EXTENSION);
            try {
                // Ensure the parent directories exist
                Files.createDirectories(issBlockFilePath.getParent());
                Files.write(issBlockFilePath, byteArrayOutputStream.toByteArray());
            } catch (IOException e) {
                log.error(
                        "Failed to write Block {} to local disk at path {}: {}",
                        blockState.blockNumber(),
                        issBlockFilePath,
                        e.getMessage(),
                        e);
            }

            String blockKey = s3IssConfig.basePath() + "/node"
                    + networkInfo.selfNodeInfo().nodeId() + "/ISS/"
                    + longToFileName(blockState.blockNumber()) + COMPLETE_BLOCK_EXTENSION
                    + COMPRESSION_ALGORITHM_EXTENSION;
            s3IssUploader.uploadFileContent(blockKey, byteArrayOutputStream.toByteArray(), "application/gzip");
            uploadIssPcesFiles(blockItems, s3IssUploader);
            log.info(
                    "Successfully uploaded ISS Block {} (Rounds {}-{}) to GCP bucket {} at path: {}",
                    blockState.blockNumber(),
                    blockState.getLowestRoundNumber(),
                    blockState.getHighestRoundNumber(),
                    s3IssConfig.bucketName(),
                    blockKey);
        } catch (Exception e) {
            log.info(
                    "Failed to upload Block {} to GCP bucket {}: {}",
                    blockState.blockNumber(),
                    s3IssConfig.bucketName(),
                    e);
        }
    }

    /**
     * Uploads Pces files to the S3 bucket.
     */
    private void uploadIssPcesFiles(final List<BlockItem> blockItems, final S3IssUploader s3IssUploader) {
        log.info("Uploading ISS pces files to S3");
        try {

            // FOR EXECUTION REVIEWERS:
            // Is this the correct way to get the last platformState modification?
            final var platformState = blockItems.stream()
                    .filter(BlockItem::hasStateChanges)
                    .map(BlockItem::stateChanges)
                    .filter(Objects::nonNull)
                    .map(StateChanges::stateChanges)
                    .flatMap(Collection::stream)
                    .filter(StateChange::hasSingletonUpdate)
                    .map(StateChange::singletonUpdate)
                    .filter(Objects::nonNull)
                    .map(SingletonUpdateChange::platformStateValue)
                    .filter(Objects::nonNull)
                    .toList()
                    .getLast();

            final var consensusSnapshot = platformState.consensusSnapshot();
            // the minimum judge birth round in the minimum judge info list in the ISS state.
            final var lowerBound = consensusSnapshot.minimumJudgeInfoList().stream()
                    .map(MinimumJudgeInfo::minimumJudgeBirthRound)
                    .min(Long::compareTo)
                    .orElse(-1L);

            final NodeId selfId = NodeId.of(networkInfo.selfNodeInfo().nodeId());
            final var pcesFiles =
                    SavedStateUtils.pcesSnapshot(configProvider.getConfiguration(), selfId, lowerBound, issRoundNumber);
            final String base = "%s/node%d/ISS/%d/pces/"
                    .formatted(
                            configProvider
                                    .getConfiguration()
                                    .getConfigData(S3IssConfig.class)
                                    .basePath(),
                            networkInfo.selfNodeInfo().nodeId(),
                            issRoundNumber);

            pcesFiles.forEach(path -> {
                final String key = Path.of(base).resolve(path.getFileName()).toString();
                s3IssUploader.uploadFile(key, path, "application/octet-stream");
                log.info(
                        "Successfully uploaded ISS Pces file {} to S3 bucket {} at path: {}",
                        path.getFileName(),
                        configProvider
                                .getConfiguration()
                                .getConfigData(S3IssConfig.class)
                                .bucketName(),
                        key);
            });

        } catch (Exception e) {
            log.error("Failed to upload ISS pces files to S3", e);
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
