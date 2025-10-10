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
 * @param blockItemBatchSize the number of items to send in a batch to block nodes
 * @param receiptEntriesBatchSize the maximum number of receipts to accumulate in a {@link com.hedera.hapi.node.state.recordcache.TransactionReceiptEntries} wrapper before writing a queue state changes item to the block stream
 * @param workerLoopSleepDuration the duration to sleep between iterations of the worker loop
 */
@ConfigData("blockStream")
public record BlockStreamConfig(
        @ConfigProperty(defaultValue = "BOTH") @NetworkProperty StreamMode streamMode,
        @ConfigProperty(defaultValue = "FILE_AND_GRPC") @NodeProperty BlockStreamWriterMode writerMode,
        @ConfigProperty(defaultValue = "/opt/hgcapp/blockStreams") @NodeProperty String blockFileDir,
        @ConfigProperty(defaultValue = "32") @NetworkProperty int hashCombineBatchSize,
        @ConfigProperty(defaultValue = "1") @NetworkProperty int roundsPerBlock,
        @ConfigProperty(defaultValue = "2s") @Min(0) @NetworkProperty Duration blockPeriod,
        @ConfigProperty(defaultValue = "256") @Min(0) @NetworkProperty int blockItemBatchSize,
        @ConfigProperty(defaultValue = "8192") @Min(1) @NetworkProperty int receiptEntriesBatchSize,
        @ConfigProperty(defaultValue = "10ms") @Min(1) @NodeProperty Duration workerLoopSleepDuration) {

    /**
     * Whether to stream to block nodes.
     */
    public boolean streamToBlockNodes() {
        return writerMode != BlockStreamWriterMode.FILE;
    }
}
