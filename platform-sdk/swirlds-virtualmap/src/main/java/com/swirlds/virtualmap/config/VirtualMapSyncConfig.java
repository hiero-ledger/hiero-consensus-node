// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.config;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import com.swirlds.config.api.validation.annotation.Positive;
import java.time.Duration;

/**
 * Configuration for the reconnect.
 *
 * @param asyncStreamTimeout                     The amount of time that an {@code AsyncInputStream} and
 *                                               {@code AsyncOutputStream} will wait before throwing a timeout.
 * @param asyncOutputStreamFlush                 In order to ensure that data is not languishing in the
 *                                               asyncOutputStream buffer a periodic flush is performed.
 * @param asyncStreamBufferSize                  The size of the buffers for async input and output streams.
 */
@ConfigData("reconnect")
public record VirtualMapSyncConfig(
        @ConfigProperty(defaultValue = "60s") Duration asyncStreamTimeout,
        @ConfigProperty(defaultValue = "8ms") Duration asyncOutputStreamFlush,
        @ConfigProperty(defaultValue = "10000") @Positive int asyncStreamBufferSize) {}
