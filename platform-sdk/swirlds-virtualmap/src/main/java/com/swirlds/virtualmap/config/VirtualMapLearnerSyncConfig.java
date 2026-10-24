// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.config;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import com.swirlds.config.api.validation.annotation.Min;
import com.swirlds.config.api.validation.annotation.Positive;
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
 *                                               so the larger effective capacity is acceptable.
 * @param asyncOutputStreamFlush                 In order to ensure that data is not languishing in the
 *                                               asyncOutputStream buffer a periodic flush is performed.
 * @param flushInterval                          During reconnect, hashes and leaves are periodically flushed to disk
 *                                               after they are processed. This interval indicates the number of items
 *                                               to collect before they are flushed. While a flush is in progress,
 *                                               threads that receive leaves, hash, or delete old leaves block once
 *                                               the next batch reaches two intervals. If zero, all data is flushed in
 *                                               the end of reconnect hashing only, and these threads never block.
 *                                               Every flush creates a new data file per store, and reconnect data
 *                                               files are rarely compacted, so a small interval leaves many small
 *                                               files behind. With flusher backpressure, up to about four intervals
 *                                               of leaves, deleted leaves, and hash chunks may be held in memory, so
 *                                               a large interval increases heap usage.
 * @param maxInFlightLeaves                      The maximum number of leaf requests sent to the teacher whose
 *                                               responses haven't been fed to the hasher yet. Senders stop
 *                                               requesting leaves while this limit is reached, which bounds the
 *                                               memory used to buffer leaf responses when hashing is slower than
 *                                               the network.
 * @param statusLogInterval                      How often the learner logs the state of its leaf pipeline
 *                                               (in-flight leaves, hashing buffer, flusher) during reconnect.
 */
// spotless:off
@ConfigData("reconnect.learner")
public record VirtualMapLearnerSyncConfig(
        @ConfigProperty(defaultValue = "16") @Positive int numSendThreads,
        @ConfigProperty(defaultValue = "16") @Positive int numReceiveThreads,
        @ConfigProperty(defaultValue = "256000000") @Positive int maxMessageSizeBytes,
        @ConfigProperty(defaultValue = "60s") Duration asyncStreamIdleTimeout,
        @ConfigProperty(defaultValue = "10000") @Positive int asyncStreamBufferSize,
        @ConfigProperty(defaultValue = "8ms") Duration asyncOutputStreamFlush,
        @Min(0) @ConfigProperty(defaultValue = "2000000") int flushInterval,
        @ConfigProperty(defaultValue = "1000000") @Positive int maxInFlightLeaves,
        @ConfigProperty(defaultValue = "60s") Duration statusLogInterval) {}
// spotless:on
