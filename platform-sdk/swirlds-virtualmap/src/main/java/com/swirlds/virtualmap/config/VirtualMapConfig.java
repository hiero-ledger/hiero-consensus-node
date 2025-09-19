// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.config;

import static com.swirlds.virtualmap.config.VirtualMapReconnectMode.PUSH;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import com.swirlds.config.api.validation.annotation.Max;
import com.swirlds.config.api.validation.annotation.Min;
import java.time.Duration;

/**
 * Instance-wide config for {@code VirtualMap}.
 *
 * @param percentHashThreads
 * 		Gets the percentage (from 0.0 to 100.0) of available processors to devote to hashing
 * 		threads. Ignored if an explicit number of threads is given via {@code virtualMap.numHashThreads}.
 * @param numHashThreads
 * 		The number of threads to devote to hashing. If not set, defaults to the number of threads implied by
 *        {@code virtualMap.percentHashThreads} and {@link Runtime#availableProcessors()}.
 * @param virtualHasherChunkHeight
 *      The number of ranks minus one to handle in a single virtual hasher task. That is, when height is
 *      1, every task takes 2 inputs. Height 2 corresponds to tasks with 4 inputs. And so on.
 * @param reconnectMode
 *      Reconnect mode. For the list of accepted values, see {@link VirtualMapReconnectMode}.
 * @param reconnectFlushInterval
 *      During reconnect, virtual nodes are periodically flushed to disk after they are hashed. This
 *      interval indicates the number of nodes to hash before they are flushed to disk. If zero, all
 *      hashed nodes are flushed in the end of reconnect hashing only.
 * @param percentCleanerThreads
 * 		Gets the percentage (from 0.0 to 100.0) of available processors to devote to cache
 * 		cleaner threads. Ignored if an explicit number of threads is given via {@code virtualMap.numCleanerThreads}.
 * @param numCleanerThreads
 * 		The number of threads to devote to cache cleaning. If not set, defaults to the number of threads implied by
 *      {@code virtualMap.percentCleanerThreads} and {@link Runtime#availableProcessors()}.
 * @param flushInterval
 * 		The interval between flushing of copies. This value defines the value of N where every Nth copy is flushed. The
 * 		value must be positive and will typically be a fairly small number, such as 20. The first copy is not flushed,
 * 		but every Nth copy thereafter is.
 * @param copyFlushCandidateThreshold
 *      Virtual root copy flush threshold. A copy can be flushed to disk only if it's size exceeds this
 *      threshold. If set to zero, size-based flushes aren't used, and copies are flushed based on {@link
 *      #flushInterval} instead.
 * @param familyThrottleThreshold
 *      Virtual root family throttle threshold. When estimated size of all unreleased copies of the same virtual
 *      root exceeds this threshold, virtual pipeline starts applying backpressure on creating new root copies.
 *      If the threshold is set to zero, this backpressure mechanism is not used.
 * @param preferredFlushQueueSize
 * 		The preferred maximum number of virtual maps waiting to be flushed. If more maps than this number are awaiting
 * 		flushing then slow down fast copies of the virtual map so that flushing can catch up.
 * @param flushThrottleStepSize
 * 		For every map copy that is awaiting flushing in excess of {@link #preferredFlushQueueSize()}, artificially
 * 		increase the amount of time required to make a fast copy by this amount of time.
 * @param maximumFlushThrottlePeriod
 * 		The maximum amount of time that any virtual map fast copy will be delayed due to a flush backlog.
 * @param validateMigrationEnabled
 *      Feature flag to enable validation during migration to the single Virtual Map (see {@code MerkleStateRoot}).
 */
@ConfigData("virtualMap")
public record VirtualMapConfig(
        @Min(0) @Max(100) @ConfigProperty(defaultValue = "50.0") double percentHashThreads,
        @Min(-1) @ConfigProperty(defaultValue = "-1") int numHashThreads,
        @Min(1) @Max(64) @ConfigProperty(defaultValue = "3") int virtualHasherChunkHeight,
        @ConfigProperty(defaultValue = PUSH) String reconnectMode,
        @Min(0) @ConfigProperty(defaultValue = "500000") int reconnectFlushInterval,
        @Min(0) @Max(100) @ConfigProperty(defaultValue = "25.0") double percentCleanerThreads,
        @Min(-1) @ConfigProperty(defaultValue = "-1") int numCleanerThreads,
        @Min(1) @ConfigProperty(defaultValue = "20") int flushInterval,
        @ConfigProperty(defaultValue = "1000000000") long copyFlushCandidateThreshold,
        @ConfigProperty(defaultValue = "5000000000") long familyThrottleThreshold,
        @ConfigProperty(defaultValue = "10000") int preferredFlushQueueSize,
        @ConfigProperty(defaultValue = "200ms") Duration flushThrottleStepSize,
        @ConfigProperty(defaultValue = "5s") Duration maximumFlushThrottlePeriod,
        @ConfigProperty(defaultValue = "false") boolean validateMigrationEnabled) {

    private static final double UNIT_FRACTION_PERCENT = 100.0;

    public int getNumHashThreads() {
        final int threads = (numHashThreads() == -1)
                ? (int) (Runtime.getRuntime().availableProcessors() * (percentHashThreads() / UNIT_FRACTION_PERCENT))
                : numHashThreads();

        return Math.max(1, threads);
    }

    public int getNumCleanerThreads() {
        final int numProcessors = Runtime.getRuntime().availableProcessors();
        final int threads = (numCleanerThreads() == -1)
                ? (int) (numProcessors * (percentCleanerThreads() / UNIT_FRACTION_PERCENT))
                : numCleanerThreads();

        return Math.max(1, threads);
    }
}
