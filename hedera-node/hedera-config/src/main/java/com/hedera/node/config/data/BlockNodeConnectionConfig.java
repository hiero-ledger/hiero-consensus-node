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
 * @param shutdownNodeOnNoBlockNodes whether to shut down the consensus node if there are no block node connections
 * @param blockNodeConnectionFileDir the directory to get the block node configuration file
 * @param blockNodeConfigFile the file containing the block nodes configurations
 * @param maxEndOfStreamsAllowed the limit of EndOfStream responses allowed within a time frame
 * @param endOfStreamTimeFrame the time frame in seconds to check for EndOfStream responses
 * @param endOfStreamScheduleDelay the delay in seconds to schedule connections after the limit is reached
 * @param maxBehindPublishersAllowed the limit of BehindPublisher responses allowed within a time frame
 * @param behindPublisherTimeFrame the time frame in seconds to check for BehindPublisher responses
 * @param behindPublisherScheduleDelay the delay in seconds to schedule connections after the BehindPublisher limit is reached
 * @param streamResetPeriod the period in hours to periodically reset the stream, once a day should be enough
 * @param protocolExpBackoffTimeframeReset if a connection has not been rescheduled during the timeframe, reset the exponential backoff
 * @param highLatencyThreshold threshold above which a block acknowledgement is considered high latency
 * @param highLatencyEventsBeforeSwitching number of consecutive high-latency events before considering switching nodes
 * @param maxBackoffDelay the maximum backoff delay for exponential backoff
 * @param grpcOverallTimeout single timeout configuration for gRPC Client construction, connectTimeout, readTimeout and pollWaitTime
 * @param connectionWorkerSleepDuration the amount of time a connection worker will sleep between handling block items (should be less than {@link #maxRequestDelay})
 * @param maxRequestDelay the maximum amount of time between sending a request to a block node
 * @param forcedSwitchRescheduleDelay the delay to reschedule a closed active connection after a forced switch
 * @param pipelineOperationTimeout timeout for pipeline onNext() and onComplete() operations to detect unresponsive block nodes
 * @param streamingRequestPaddingBytes the base overhead (in bytes) that is applied to every pending request when estimating the request size
 * @param streamingRequestItemPaddingBytes the amount of additional bytes to include for each block item when estimating the request size
 * @param blockNodeStatusTimeout the timeout for retrieving block node server status (millisecond precision)
 */
@ConfigData("blockNode")
public record BlockNodeConnectionConfig(
        @ConfigProperty(defaultValue = "false") @NodeProperty
        boolean shutdownNodeOnNoBlockNodes,

        @ConfigProperty(defaultValue = "data/config") @NodeProperty
        String blockNodeConnectionFileDir,

        @ConfigProperty(defaultValue = "block-nodes.json") @NodeProperty
        String blockNodeConfigFile,

        @ConfigProperty(defaultValue = "5") @NodeProperty int maxEndOfStreamsAllowed,
        @ConfigProperty(defaultValue = "30s") @NodeProperty Duration endOfStreamTimeFrame,
        @ConfigProperty(defaultValue = "30s") @NodeProperty Duration endOfStreamScheduleDelay,
        @ConfigProperty(defaultValue = "1") @NodeProperty int maxBehindPublishersAllowed,
        @ConfigProperty(defaultValue = "30s") @NodeProperty Duration behindPublisherTimeFrame,
        @ConfigProperty(defaultValue = "30s") @NodeProperty Duration behindPublisherScheduleDelay,
        @ConfigProperty(defaultValue = "24h") @NodeProperty Duration streamResetPeriod,
        @ConfigProperty(defaultValue = "30s") @NodeProperty Duration protocolExpBackoffTimeframeReset,
        @ConfigProperty(defaultValue = "30s") @NodeProperty Duration highLatencyThreshold,
        @ConfigProperty(defaultValue = "5") @NodeProperty int highLatencyEventsBeforeSwitching,
        @ConfigProperty(defaultValue = "10s") @NodeProperty Duration maxBackoffDelay,
        @ConfigProperty(defaultValue = "30s") @NodeProperty Duration grpcOverallTimeout,

        @ConfigProperty(defaultValue = "10ms") @NetworkProperty
        Duration connectionWorkerSleepDuration,

        @ConfigProperty(defaultValue = "100ms") @NetworkProperty
        Duration maxRequestDelay,

        @ConfigProperty(defaultValue = "180s") @NodeProperty Duration forcedSwitchRescheduleDelay,
        @ConfigProperty(defaultValue = "3s") @NodeProperty Duration pipelineOperationTimeout,

        @ConfigProperty(defaultValue = "100") @Min(0) @NetworkProperty
        int streamingRequestPaddingBytes,

        @ConfigProperty(defaultValue = "5") @Min(0) @NetworkProperty
        int streamingRequestItemPaddingBytes,

        @ConfigProperty(defaultValue = "1s") @NodeProperty Duration blockNodeStatusTimeout) {}
