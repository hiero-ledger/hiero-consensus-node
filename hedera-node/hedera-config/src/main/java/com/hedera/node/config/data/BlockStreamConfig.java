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
 * @param hashCombineBatchSize the number of items to hash in a batch
 * @param roundsPerBlock the number of rounds per block
 * @param blockPeriod the block period
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

        @ConfigProperty(defaultValue = "32") @NetworkProperty
        int hashCombineBatchSize,

        @ConfigProperty(defaultValue = "1") @NetworkProperty int roundsPerBlock,

        @ConfigProperty(defaultValue = "2s") @Min(0) @NetworkProperty
        Duration blockPeriod,

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

        @ConfigProperty(defaultValue = "false") @NetworkProperty
        boolean enableStateProofs,

        @ConfigProperty(defaultValue = "4096") @Min(512) @NetworkProperty
        int blockFileBufferOuterSizeKb,

        @ConfigProperty(defaultValue = "1024") @Min(128) @NetworkProperty
        int blockFileBufferInnerSizeKb,

        @ConfigProperty(defaultValue = "256") @Min(64) @NetworkProperty
        int blockFileBufferGzipSizeKb) {

    /**
     * Whether to stream to block nodes.
     */
    public boolean streamToBlockNodes() {
        return writerMode != BlockStreamWriterMode.FILE;
    }
}
