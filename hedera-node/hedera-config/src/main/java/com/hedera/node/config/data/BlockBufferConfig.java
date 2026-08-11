// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.data;

import com.hedera.node.config.NetworkProperty;
import com.hedera.node.config.NodeProperty;
import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import com.swirlds.config.api.validation.annotation.Min;
import java.time.Duration;

/**
 * Configuration settings related to the block buffer.
 *
 * @param maxBlocks the maximum number of blocks that can be buffered before the buffer is considered full
 * @param maxBytes the maximum number of bytes that can be buffered before the buffer is considered full. This value can
 *                 be specified either in bytes (no postfix), kilobytes (k|K), megabytes (m|M), or gigabytes (g|G) by
 *                 appending the appropriate postfix to the value. For example, a 1 GB value may be represented as one
 *                 of the following: 1G, 1024M, 1048576K, 1073741824. The minimum allowed size is 10 MB.
 * @param workerInterval interval to perform periodic tasks related to the block buffer (e.g. pruning and persisting
 *                       buffer to disk)
 * @param actionStageThreshold the threshold (as a percentage from 0.0 to 100.0) at which proactive measures are
 *                             taken to attempt faster buffer recovery. This threshold is measured against the
 *                             current saturation level of the buffer. (For example, a value of '20.0' means
 *                             once the buffer saturated reaches 20% or higher, proactive measures will be taken
 *                             such as attempting to connect to a different block node.)
 * @param actionGracePeriod the period between buffer recovery action attempts. After an action has been performed to
 *                          attempt buffer recovery, there will be a grace period before the next recovery attempt is
 *                          triggered. That delay is this configuration property.
 * @param recoveryThreshold the threshold (as a percentage from 0.0 to 100.0) of which the buffer saturation level must
 *                          be decreased by before the buffer is considered "in recovery" and back pressure (if enabled)
 *                          can be removed. (For example, a value of '85.0' means at least 15% of the buffer capacity
 *                          must be available. Said another way: the buffer saturation must be at or below 85% before
 *                          the buffer is considered recovered.)
 * @param isBufferPersistenceEnabled true if periodic persistence to disk of the block buffer is permitted, else false
 * @param bufferDirectory the root directory that the block buffer will be persisted into, if enabled
 * @param ackedBlocksToRetain the number of acknowledged blocks to retain in the buffer at any given time.
 *                            This is a "soft" limit: when the buffer is under pressure from unacknowledged blocks
 *                            and pushing against {@code maxBlocks} or {@code maxBytes}, acknowledged blocks below this
 *                            floor may still be pruned to make room. When the block node is healthy, this floor allows
 *                            the buffer to remain small while still preserving a recent window of acknowledged blocks
 *                            in case the block node re-requests one.
 */
// spotless:off
@ConfigData("blockStream.buffer")
public record BlockBufferConfig(
        @ConfigProperty(defaultValue = "30") @Min(0) @NetworkProperty int maxBlocks,
        @ConfigProperty(defaultValue = "15g") @NetworkProperty String maxBytes,
        @ConfigProperty(defaultValue = "1s") @Min(1) @NetworkProperty Duration workerInterval,
        @ConfigProperty(defaultValue = "50.0") @Min(0) @NetworkProperty double actionStageThreshold,
        @ConfigProperty(defaultValue = "20s") @Min(0) @NetworkProperty Duration actionGracePeriod,
        @ConfigProperty(defaultValue = "85.0") @Min(0) @NetworkProperty double recoveryThreshold,
        @ConfigProperty(defaultValue = "true") @NodeProperty boolean isBufferPersistenceEnabled,
        @ConfigProperty(defaultValue = "/opt/hgcapp/blockStreams/buffer") @NodeProperty String bufferDirectory,
        @ConfigProperty(defaultValue = "10") @Min(0) @NetworkProperty int ackedBlocksToRetain) {}
// spotless:on
