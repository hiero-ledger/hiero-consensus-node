// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.config;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
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
 * @param asyncOutputStreamFlush                 In order to ensure that data is not languishing in the
 *                                               asyncOutputStream buffer a periodic flush is performed.
 */
// spotless:off
@ConfigData("vmap.sync.learner")
public record VirtualMapLearnerSyncConfig(
        @ConfigProperty(defaultValue = "16") @Positive int numSendThreads,
        @ConfigProperty(defaultValue = "16") @Positive int numReceiveThreads,
        @ConfigProperty(defaultValue = "256000000") @Positive int maxMessageSizeBytes,
        @ConfigProperty(defaultValue = "60s") Duration asyncStreamIdleTimeout,
        @ConfigProperty(defaultValue = "10000") @Positive int asyncStreamBufferSize,
        @ConfigProperty(defaultValue = "8ms") Duration asyncOutputStreamFlush) {}
// spotless:on
