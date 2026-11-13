// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.config;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.validation.ConfigViolation;
import com.swirlds.config.api.validation.annotation.ConstraintMethod;
import com.swirlds.config.api.validation.annotation.Max;
import com.swirlds.config.api.validation.annotation.Positive;
import com.swirlds.config.extensions.validators.DefaultConfigViolation;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;

/**
 * Configuration for the virtual map learner sync.
 *
 * @param numSendThreads                         The number of threads to use for sending data in the learner sync.
 * @param numReceiveThreads                      The number of threads to use for receiving data in the learner sync.
 * @param maxMessageSizeBytes                    The maximum size of a message in bytes to receive from the teacher.
 * @param asyncStreamIdleTimeout                 The amount of time that an {@code AsyncInputStream} and
 *                                               {@code AsyncOutputStream} will wait before throwing a timeout.
 * @param asyncStreamBufferSize                  The size of the buffers for async input and output streams.
 *                                               The output stream rounds this value up to the next power of two
 *                                               (the default 10000 becomes 16384) because its lock-free ring buffer
 *                                               indexes slots with a bit mask. The default has no strict derivation,
 *                                               so the larger effective capacity is acceptable. At most
 *                                               1073741824 (2^30), the largest capacity of the ring buffer,
 *                                               which is allocated in full up front.
 * @param asyncOutputStreamFlush                 In order to ensure that data is not languishing in the
 *                                               asyncOutputStream buffer a periodic flush is performed.
 * @param flushInterval                          During reconnect, hashes and leaves are periodically flushed to disk
 *                                               after they are processed. A flush is started once any of the following
 *                                               reaches this value: the number of hash slots in collected hash chunks,
 *                                               the number of updated leaves, or the number of deleted leaves. While a
 *                                               flush is running, threads that receive leaves, hash, or delete old
 *                                               leaves block once the next batch reaches this value, so about two
 *                                               batches are kept in memory. Blocked receivers stop reading from the
 *                                               teacher, so a single flush must complete well within the teacher's
 *                                               {@code reconnect.teacher.asyncStreamIdleTimeout} and
 *                                               {@code reconnect.socketTimeout}, otherwise reconnect fails. This
 *                                               blocking is what throttles receiving to the disk write rate, so the
 *                                               value must be positive. Every flush creates a new data file per
 *                                               store, and reconnect data files are rarely compacted, so a small
 *                                               interval leaves many small files behind, while a large interval
 *                                               increases heap usage.
 * @param leafBacklogThrottleThreshold           The learner stops requesting leaves while its leaf backlog is at this
 *                                               threshold or above. The backlog is the number of leaves requested from
 *                                               the teacher, but not stored to disk yet together with their hashes.
 *                                               Leaves leave the backlog only when they are flushed, so the threshold
 *                                               must be at least twice {@code flushInterval}, otherwise the backlog may
 *                                               never fill a flush. This is checked when the configuration is built. A
 *                                               reconnect may use a larger threshold, if the hash chunks of the map are
 *                                               large compared to {@code flushInterval}. This bounds the memory used by
 *                                               leaves waiting for hashing or flushing, when leaves are received faster
 *                                               than they are hashed, for example when hashing threads are blocked on
 *                                               flusher backpressure. Each leaf takes a few hundred bytes of heap.
 */
// spotless:off
@ConfigData("reconnect.learner")
public record VirtualMapLearnerSyncConfig(
        @ConfigProperty(defaultValue = "16") @Positive int numSendThreads,
        @ConfigProperty(defaultValue = "16") @Positive int numReceiveThreads,
        @ConfigProperty(defaultValue = "256000000") @Positive int maxMessageSizeBytes,
        @ConfigProperty(defaultValue = "60s") Duration asyncStreamIdleTimeout,
        @ConfigProperty(defaultValue = "10000") @Positive @Max(1073741824) int asyncStreamBufferSize,
        @ConfigProperty(defaultValue = "8ms") Duration asyncOutputStreamFlush,
        @ConfigProperty(defaultValue = "2000000") @Positive int flushInterval,
        @ConfigProperty(defaultValue = "4000000") @Positive @ConstraintMethod("validateLeafBacklogThrottleThreshold") int leafBacklogThrottleThreshold) {
// spotless:on

    private static final String LEAF_BACKLOG_THROTTLE_THRESHOLD = "reconnect.learner.leafBacklogThrottleThreshold";

    /// Checks that [#leafBacklogThrottleThreshold()] is at least twice [#flushInterval()]. Dirty leaves leave the
    /// learner's leaf backlog only when they are flushed, and a flush starts only when the flusher has collected
    /// `flushInterval` items, so a smaller backlog may never fill a flush. Twice the interval also leaves room
    /// for the next flush to fill up while the current one is running.
    ///
    /// @param configuration the configuration
    /// @return a violation, or null if the threshold is valid
    @Nullable
    public ConfigViolation validateLeafBacklogThrottleThreshold(@NonNull final Configuration configuration) {
        if (leafBacklogThrottleThreshold >= 2L * flushInterval) {
            return null;
        }
        return new DefaultConfigViolation(
                LEAF_BACKLOG_THROTTLE_THRESHOLD,
                Integer.toString(leafBacklogThrottleThreshold),
                true,
                "must be at least twice reconnect.learner.flushInterval (" + flushInterval + ")");
    }
}
