// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.builder;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;

/**
 * Configuration for selecting consensus module implementations via ServiceLoader.
 *
 * <p>Each property specifies the JPMS module name (from {@code module-info.java}) of the desired
 * implementation. When empty (the default), the sole available provider is used. When multiple
 * providers are on the module path, explicit selection is <b>required</b> to guarantee deterministic
 * behavior across all nodes.
 *
 * @param eventCreator JPMS module name for the {@code EventCreatorModule} implementation
 * @param eventIntake  JPMS module name for the {@code EventIntakeModule} implementation
 * @param pces         JPMS module name for the {@code PcesModule} implementation
 * @param hashgraph    JPMS module name for the {@code HashgraphModule} implementation
 * @param gossip       JPMS module name for the {@code GossipModule} implementation
 * @param reconnect    JPMS module name for the {@code ReconnectModule} implementation
 */
@ConfigData("modules")
public record ModulesConfig(
        @ConfigProperty(defaultValue = "org.hiero.consensus.event.creator.impl")
        String eventCreator,

        @ConfigProperty(defaultValue = "org.hiero.consensus.event.intake.impl")
        String eventIntake,

        @ConfigProperty(defaultValue = "org.hiero.consensus.pces.impl")
        String pces,

        @ConfigProperty(defaultValue = "org.hiero.consensus.hashgraph.impl")
        String hashgraph,

        @ConfigProperty(defaultValue = "org.hiero.consensus.gossip.impl")
        String gossip,

        @ConfigProperty(defaultValue = "org.hiero.consensus.reconnect.impl")
        String reconnect) {}
