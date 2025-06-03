// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.config;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.model.event.AncientMode;

/**
 * Configuration for event handling inside the platform.
 *
 * @param eventStreamQueueCapacity          capacity of the blockingQueue from which we take events and write to
 *                                          EventStream files
 * @param eventsLogPeriod                   period of generating eventStream file
 * @param eventsLogDir                      eventStream files will be generated in this directory.
 * @param enableEventStreaming              enable stream event to server.
 * @param useBirthRoundAncientThreshold     if true, use birth rounds instead of generations for deciding if an event is
 *                                          ancient or not. Once this setting has been enabled on a network, it can
 *                                          never be disabled again (migration pathway is one-way).
 */
@ConfigData("event")
public record EventConfig(
        @ConfigProperty(defaultValue = "5000") int eventStreamQueueCapacity,
        @ConfigProperty(defaultValue = "5") long eventsLogPeriod,
        @ConfigProperty(defaultValue = "/opt/hgcapp/eventsStreams") String eventsLogDir,
        @ConfigProperty(defaultValue = "true") boolean enableEventStreaming,
        @ConfigProperty(defaultValue = "true") boolean useBirthRoundAncientThreshold) {

    /**
     * @return the {@link AncientMode} based on useBirthRoundAncientThreshold
     */
    @NonNull
    public AncientMode getAncientMode() {
        if (useBirthRoundAncientThreshold()) {
            return AncientMode.BIRTH_ROUND_THRESHOLD;
        } else {
            return AncientMode.GENERATION_THRESHOLD;
        }
    }
}
