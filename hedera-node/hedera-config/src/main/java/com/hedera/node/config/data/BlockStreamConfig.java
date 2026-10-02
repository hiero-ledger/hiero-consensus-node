// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.data;

import com.hedera.node.config.NetworkProperty;
import com.hedera.node.config.NodeProperty;
import com.hedera.node.config.types.BlockStreamWriterMode;
import com.hedera.node.config.types.StreamMode;
import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import com.swirlds.config.api.validation.annotation.Min;
import java.time.Duration;

/**
 * Configuration for the block stream.
 * @param streamMode Value of RECORDS disables the block stream; BOTH enables it
 * @param writerMode if we are writing to a file or gRPC stream
 * @param blockFileDir directory to store block files
 * @param roundsPerBlock the number of rounds per block
 * @param blockPeriod the block period
 * @param maxBlockSizeBytes the maximum serialized block size in bytes before the block-size circuit breaker engages;
 *                          zero disables the circuit breaker. In {@code StreamMode.BOTH} (preview block stream),
 *                          the circuit breaker suppresses further block-stream savepoint output for the rest of the
 *                          block. In {@code StreamMode.BLOCKS} (canonical block stream), it additionally causes
 *                          subsequent non-signature submitted transactions in that block to be rejected with {@code THROTTLED_AT_CONSENSUS}
 *                          with normal consensus throttle fees; scheduled executions are deferred until capacity is available
 * @param maxBlockSizeIngestGateMaxAge maximum age of the ingest block-full signal; zero disables the ingest gate
 * @param maxBlockSizeLimitEnabled feature flag for the max-block-size limit; {@code false} disables both the
 *                                 in-block dispatch throttle (see {@link #maxBlockSizeBytes}, {@code StreamMode.BLOCKS})
 *                                 and the ingest gate (see {@link #maxBlockSizeIngestGateMaxAge}) regardless of their
 *                                 configured thresholds. Does not affect the unrelated {@code StreamMode.BOTH}
 *                                 savepoint-output circuit breaker
 * @param pauseApplicationTransactionsOnBlockFull when enabled, pause application inclusion in events at the
 *                                             execution cutoff until the next block opens
 * @param receiptEntriesBatchSize the maximum number of receipts to accumulate in a {@link com.hedera.hapi.node.state.recordcache.TransactionReceiptEntries} wrapper before writing a queue state changes item to the block stream
 * @param maxReadDepth the max allowed depth of nested protobuf messages
 * @param maxReadBytesSize the max size in bytes of protobuf messages to read
 * @param blockFileBufferOuterSizeKb block file writer outer buffer size (in kilobytes) (see FileBlockItemWriter#openBlock(long) for details)
 * @param blockFileBufferInnerSizeKb block file writer inner buffer size (in kilobytes) (see FileBlockItemWriter#openBlock(long) for details)
 * @param blockFileBufferGzipSizeKb block file writer GZIP buffer size (in kilobytes) (see FileBlockItemWriter#openBlock(long) for details)
 */
@ConfigData("blockStream")
public record BlockStreamConfig(
        @ConfigProperty(defaultValue = "BOTH") @NetworkProperty
        StreamMode streamMode,

        @ConfigProperty(defaultValue = "FILE_AND_GRPC") @NodeProperty
        BlockStreamWriterMode writerMode,

        @ConfigProperty(defaultValue = "/opt/hgcapp/blockStreams") @NodeProperty
        String blockFileDir,

        @ConfigProperty(defaultValue = "1") @NetworkProperty int roundsPerBlock,

        @ConfigProperty(defaultValue = "2s") @Min(0) @NetworkProperty
        Duration blockPeriod,

        @ConfigProperty(defaultValue = "52428800") @Min(0) @NetworkProperty
        long maxBlockSizeBytes,

        @ConfigProperty(defaultValue = "2s") @Min(0) @NodeProperty
        Duration maxBlockSizeIngestGateMaxAge,

        @ConfigProperty(defaultValue = "true") @NetworkProperty
        boolean maxBlockSizeLimitEnabled,

        @ConfigProperty(defaultValue = "true") @NodeProperty boolean pauseApplicationTransactionsOnBlockFull,

        @ConfigProperty(defaultValue = "8192") @Min(1) @NetworkProperty
        int receiptEntriesBatchSize,

        @ConfigProperty(defaultValue = "10ms") @Min(1) @NodeProperty
        Duration workerLoopSleepDuration,

        @ConfigProperty(defaultValue = "100") @Min(1) @NodeProperty
        int maxConsecutiveScheduleSecondsToProbe,

        @ConfigProperty(defaultValue = "1s") @Min(1) @NodeProperty
        Duration quiescedHeartbeatInterval,

        @ConfigProperty(defaultValue = "512") @NodeProperty int maxReadDepth,

        @ConfigProperty(defaultValue = "500000000") @NodeProperty
        int maxReadBytesSize,

        @ConfigProperty(defaultValue = "4096") @Min(512) @NetworkProperty
        int blockFileBufferOuterSizeKb,

        @ConfigProperty(defaultValue = "1024") @Min(128) @NetworkProperty
        int blockFileBufferInnerSizeKb,

        @ConfigProperty(defaultValue = "256") @Min(64) @NetworkProperty
        int blockFileBufferGzipSizeKb,

        @ConfigProperty(defaultValue = "false") @NetworkProperty
        boolean enableCutover,

        @ConfigProperty(defaultValue = "true") @NetworkProperty
        boolean streamWrappedRecordBlocks,

        @ConfigProperty(defaultValue = "false") @NodeProperty
        boolean enhancedObservabilityEnabled) {

    /**
     * Whether the node should maintain an active stream to block nodes — true when the main
     * stream writes via gRPC <b>or</b> the WRB path is enabled (the WRB writer also publishes
     * through {@code BlockBufferService}).
     */
    public boolean streamToBlockNodes() {
        return writerMode != BlockStreamWriterMode.FILE;
    }
}
