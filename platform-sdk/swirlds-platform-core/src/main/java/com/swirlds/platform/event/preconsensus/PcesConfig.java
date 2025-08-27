// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.preconsensus;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import com.swirlds.config.api.validation.annotation.Min;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Configuration for preconsensus event storage.
 *
 * @param writeQueueCapacity                   the queue capacity for preconsensus events waiting to be written to disk
 * @param minimumRetentionPeriod               the minimum amount of time that preconsensus events should be stored on
 *                                             disk. At a minimum, should exceed the length of time between state
 *                                             saving.
 * @param preferredFileSizeMegabytes           the preferred file size for preconsensus event files. Not a strong
 *                                             guarantee on file size, more of a suggestion.
 * @param bootstrapSpan                        when first starting up a preconsensus event file manager, the running
 *                                             average for the span utilization will not have any values in it. Use this
 *                                             value until the running average represents real data. Once the running
 *                                             average for the span utilization becomes available, then this value is
 *                                             ignored.
 * @param spanUtilizationRunningAverageLength  the preconsensus event stream tracks the running average of the span
 *                                             utilization in event stream files. It uses this running average in a
 *                                             heuristic when deciding the upper bound of a new file. This value
 *                                             controls the number of recent files that are considered when computing
 *                                             the running average.
 * @param bootstrapSpanOverlapFactor           when choosing the pan for a new file during the bootstrapping phase,
 *                                             multiply the running average of previous file span utilization by this
 *                                             factor. Choosing a value too large will cause unnecessary un-utilized
 *                                             span if the node crashes. Choosing a value too small may case event files
 *                                             to be smaller than the preferred size. Should be larger than the
 *                                             non-boostrap span overlap factor to allow for faster convergence on a
 *                                             sane file size at startup time.
 * @param spanOverlapFactor                    when choosing the span for a new file, multiply the running average of
 *                                             previous file span utilization by this factor. Choosing a value too large
 *                                             will cause unnecessary un-utilized span if the node crashes. Choosing a
 *                                             value too small may case event files to be smaller than the preferred
 *                                             size.
 * @param minimumSpan                          when creating a new file, make sure it at least has this much available
 *                                             span. This is puts a sane "floor" on the span heuristic, so that we never
 *                                             attempt to open a file that doesn't have capacity for events, regardless
 *                                             of the required span to do so. If properly configured and under steady
 *                                             state operation, this capacity is unlikely to be a limiting factor on the
 *                                             span of files.
 * @param permitGaps                           if false (default) then throw an exception if we attempt to load
 *                                             preconsensus events and notice gaps in the file sequence. This is only
 *                                             possible if a preconsensus event file has been deleted out of band. This
 *                                             setting is present only to allow emergency manual action. In general,
 *                                             allowing gaps is likely to either lead to ISSes or, more likely, cause
 *                                             events to be added to the hashgraph without their parents being added. Or
 *                                             Both. Use this with caution.
 * @param databaseDirectory                    the directory where preconsensus events will be stored, relative to
 *                                             {@link
 *                                             com.swirlds.common.config.StateCommonConfig#savedStateDirectory()}.
 * @param replayQueueSize                      the size of the queue used for holding preconsensus events that are
 *                                             waiting to be replayed
 * @param replayHashPoolSize                   the number of threads used for hashing events during replay
 * @param copyRecentStreamToStateSnapshots     if true, then copy recent PCES files into the saved state snapshot
 *                                             directories every time we take a state snapshot. The files copied are
 *                                             guaranteed to contain all non-ancient events w.r.t. the state snapshot.
 * @param compactLastFileOnStartup             if true, then compact the last file's span on startup.
 * @param forceIgnorePcesSignatures            if true, then ignore the signatures on preconsensus events. Note: This is
 *                                             a TEST ONLY setting. It must never be enabled in production.
 * @param roundDurabilityBufferHeartbeatPeriod the period of the heartbeats sent to the round durability buffer
 *                                             component, which uses the opportunity to check for and log when a round
 *                                             has been stuck for too long
 * @param suspiciousRoundDurabilityDuration    the duration after which a round is considered stuck in the round
 *                                             durability buffer component
 * @param replayHealthThreshold                if the system is unhealthy (i.e. overloaded) for more than this amount of
 *                                             time, pause PCES replay until the system is able to catch up.
 * @param limitReplayFrequency                 if true, then directly limit the replay frequency of preconsensus events
 * @param maxEventReplayFrequency              the maximum number of events that can be replayed per second
 * @param inlinePcesSyncOption                 when to sync the preconsensus event file to disk (applies only to inline
 *                                             PCES)
 * @param pcesFileWriterType                   type of pces writer to be used in default environment (Linux for now, Mac has its override at {@link #macPcesFileWriterType}
 * @param macPcesFileWriterType                override for pcesFileWriterType to be used on Mac, as FileChannel is 150x slower there
 */
@ConfigData("event.preconsensus")
public record PcesConfig(
        @ConfigProperty(defaultValue = "1000") int writeQueueCapacity,
        @ConfigProperty(defaultValue = "1h") Duration minimumRetentionPeriod,
        @ConfigProperty(defaultValue = "10") int preferredFileSizeMegabytes,
        @ConfigProperty(defaultValue = "50") int bootstrapSpan,
        @ConfigProperty(defaultValue = "5") int spanUtilizationRunningAverageLength,
        @Min(1) @ConfigProperty(defaultValue = "10") double bootstrapSpanOverlapFactor,
        @Min(1) @ConfigProperty(defaultValue = "1.2") double spanOverlapFactor,
        @ConfigProperty(defaultValue = "5") int minimumSpan,
        @ConfigProperty(defaultValue = "false") boolean permitGaps,
        @ConfigProperty(defaultValue = "preconsensus-events") Path databaseDirectory,
        @ConfigProperty(defaultValue = "1024") int replayQueueSize,
        @ConfigProperty(defaultValue = "8") int replayHashPoolSize,
        @ConfigProperty(defaultValue = "true") boolean copyRecentStreamToStateSnapshots,
        @ConfigProperty(defaultValue = "true") boolean compactLastFileOnStartup,
        @ConfigProperty(defaultValue = "false") boolean forceIgnorePcesSignatures,
        @ConfigProperty(defaultValue = "1m") Duration roundDurabilityBufferHeartbeatPeriod,
        @ConfigProperty(defaultValue = "1m") Duration suspiciousRoundDurabilityDuration,
        @ConfigProperty(defaultValue = "1ms") Duration replayHealthThreshold,
        @ConfigProperty(defaultValue = "true") boolean limitReplayFrequency,
        @ConfigProperty(defaultValue = "5000") int maxEventReplayFrequency,
        @ConfigProperty(defaultValue = "EVERY_SELF_EVENT") FileSyncOption inlinePcesSyncOption,
        @ConfigProperty(defaultValue = "OUTPUT_STREAM") PcesFileWriterType pcesFileWriterType,
        @ConfigProperty(defaultValue = "OUTPUT_STREAM") PcesFileWriterType macPcesFileWriterType) {}
