// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.data;

import com.hedera.node.config.NetworkProperty;
import com.hedera.node.config.NodeProperty;
import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import com.swirlds.config.api.validation.annotation.Min;
import java.time.Duration;

/**
 * Configuration for Connecting to Block Nodes.
 * @param blockNodeConnectionFileDir the directory to get the block node configuration file
 * @param maxEndOfStreamsAllowed the limit of EndOfStream responses allowed within a time frame
 * @param endOfStreamTimeFrame the time frame in seconds to check for EndOfStream responses
 * @param maxBehindPublishersAllowed the limit of BehindPublisher responses allowed within a time frame
 * @param behindPublisherTimeFrame the time frame in seconds to check for BehindPublisher responses
 * @param behindPublisherIgnorePeriod the duration to ignore BehindPublisher messages after receiving the first one
 * @param streamResetPeriod the period in hours to periodically reset the stream, once a day should be enough
 * @param streamResetPeriodJitter the maximum jitter subtracted from streamResetPeriod when scheduling periodic resets, to avoid thundering herd
 * @param highLatencyThreshold threshold above which a block acknowledgement is considered high latency
 * @param highLatencyEventsBeforeSwitching number of consecutive high-latency events before considering switching nodes
 * @param grpcOverallTimeout single timeout configuration for gRPC Client construction, connectTimeout, readTimeout and pollWaitTime
 * @param connectionWorkerSleepDuration the amount of time a connection worker will sleep between handling block items (should be less than {@link #maxRequestDelay})
 * @param maxRequestDelay the maximum amount of time between sending a request to a block node
 * @param pipelineOperationTimeout timeout for pipeline onNext() and onComplete() operations to detect unresponsive block nodes
 * @param streamingRequestPaddingBytes the base overhead (in bytes) that is applied to every pending request when estimating the request size
 * @param streamingRequestItemPaddingBytes the amount of additional bytes to include for each block item when estimating the request size
 * @param blockNodeStatusTimeout the timeout for retrieving block node server status (millisecond precision)
 * @param defaultMessageHardLimitBytes the default message hard limit (in bytes) used when a block node does not specify its own hard limit. Default is 37748736 bytes (36 MB).
 * @param connectionMonitorCheckIntervalMillis the amount of time (in milliseconds) between checking the health of block node connectivity
 * @param connectionStallThresholdMillis the amount of time needed to elapse (in milliseconds) between connection worker loop invocations before a connection is considered stalled
 * @param connectCoolDownSeconds the minimum amount of time (in seconds) to permit reconnecting to a block node
 */
// spotless:off
@ConfigData("blockNode")
public record BlockNodeConnectionConfig(
        @ConfigProperty(defaultValue = "data/config") @NodeProperty String blockNodeConnectionFileDir,
        @ConfigProperty(defaultValue = "5") @NodeProperty int maxEndOfStreamsAllowed,
        @ConfigProperty(defaultValue = "30s") @NodeProperty Duration endOfStreamTimeFrame,
        @ConfigProperty(defaultValue = "1") @NodeProperty int maxBehindPublishersAllowed,
        @ConfigProperty(defaultValue = "30s") @NodeProperty Duration behindPublisherTimeFrame,
        @ConfigProperty(defaultValue = "5s") @NodeProperty Duration behindPublisherIgnorePeriod,
        @ConfigProperty(defaultValue = "24h") @NodeProperty Duration streamResetPeriod,
        @ConfigProperty(defaultValue = "30m") @NodeProperty Duration streamResetPeriodJitter,
        @ConfigProperty(defaultValue = "30s") @NodeProperty Duration highLatencyThreshold,
        @ConfigProperty(defaultValue = "5") @NodeProperty int highLatencyEventsBeforeSwitching,
        @ConfigProperty(defaultValue = "30s") @NodeProperty Duration grpcOverallTimeout,
        @ConfigProperty(defaultValue = "10ms") @NetworkProperty Duration connectionWorkerSleepDuration,
        @ConfigProperty(defaultValue = "100ms") @NetworkProperty Duration maxRequestDelay,
        @ConfigProperty(defaultValue = "3s") @NodeProperty Duration pipelineOperationTimeout,
        @ConfigProperty(defaultValue = "100") @Min(0) @NetworkProperty int streamingRequestPaddingBytes,
        @ConfigProperty(defaultValue = "5") @Min(0) @NetworkProperty int streamingRequestItemPaddingBytes,
        @ConfigProperty(defaultValue = "1s") @NodeProperty Duration blockNodeStatusTimeout,
        @ConfigProperty(defaultValue = "37748736") @Min(1) @NodeProperty long defaultMessageHardLimitBytes,
        @ConfigProperty(defaultValue = "200") @Min(1) @NetworkProperty int connectionMonitorCheckIntervalMillis,
        @ConfigProperty(defaultValue = "250") @Min(10) @NetworkProperty int connectionStallThresholdMillis,
        @ConfigProperty(defaultValue = "15") @Min(0) @NetworkProperty int connectCoolDownSeconds) {
}
// spotless:on
