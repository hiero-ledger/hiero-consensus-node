// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.config;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;

/**
 * Configuration for event handling inside the platform.
 *
 * @param eventStreamQueueCapacity      capacity of the blockingQueue from which we take events and write to EventStream
 *                                      files
 * @param eventsLogPeriod               period of generating eventStream file
 * @param eventsLogDir                  eventStream files will be generated in this directory.
 * @param enableEventStreaming          enable stream event to server.
 */
@ConfigData("event")
public record EventConfig(
        @ConfigProperty(defaultValue = "5000") int eventStreamQueueCapacity,
        @ConfigProperty(defaultValue = "5") long eventsLogPeriod,
        @ConfigProperty(defaultValue = "/opt/hgcapp/eventsStreams") String eventsLogDir,
        @ConfigProperty(defaultValue = "true") boolean enableEventStreaming) {}
