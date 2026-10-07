// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.config;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import com.swirlds.config.api.validation.annotation.Positive;
import java.time.Duration;

/**
 * Configuration for the virtual map teacher sync.
 *
 * @param numReceiveThreads                      The number of threads to use for receiving data in the teacher sync.
 * @param maxMessageSizeBytes                    The maximum size of a message in bytes to receive from the learner.
 * @param asyncStreamIdleTimeout                 The amount of time that an {@code AsyncInputStream} and
 *                                               {@code AsyncOutputStream} will wait before throwing a timeout.
 * @param asyncStreamBufferSize                  The size of the buffers for async input and output streams.
 *                                               The output stream rounds this value up to the next power of two
 *                                               (the default 10000 becomes 16384) because its lock-free ring buffer
 *                                               indexes slots with a bit mask. The default has no strict derivation,
 *                                               so the larger effective capacity is acceptable.
 * @param asyncOutputStreamFlush                 In order to ensure that data is not languishing in the
 *                                               asyncOutputStream buffer a periodic flush is performed.
 */
@ConfigData("reconnect.teacher")
public record VirtualMapTeacherSyncConfig(
        @ConfigProperty(defaultValue = "16") @Positive int numReceiveThreads,
        @ConfigProperty(defaultValue = "8000000") @Positive int maxMessageSizeBytes,
        @ConfigProperty(defaultValue = "600s") Duration asyncStreamIdleTimeout,
        @ConfigProperty(defaultValue = "10000") @Positive int asyncStreamBufferSize,
        @ConfigProperty(defaultValue = "8ms") Duration asyncOutputStreamFlush) {}
