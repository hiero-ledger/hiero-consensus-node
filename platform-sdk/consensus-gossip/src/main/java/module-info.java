// SPDX-License-Identifier: Apache-2.0
import com.swirlds.config.api.ConfigurationExtension;
import org.hiero.consensus.gossip.config.GossipConfigurationExtension;

module org.hiero.consensus.gossip {
    exports org.hiero.consensus.gossip;
    exports org.hiero.consensus.gossip.config;

    requires transitive com.swirlds.component.framework;
    requires transitive com.swirlds.config.api;
    requires transitive org.hiero.consensus.model;
    requires transitive org.hiero.consensus.concurrent;
    requires transitive org.hiero.consensus.utility;

    requires com.fasterxml.jackson.core;
    requires com.fasterxml.jackson.databind;
    requires com.fasterxml.jackson.dataformat.yaml;
    requires static transitive com.github.spotbugs.annotations;
    requires com.swirlds.metrics.api;
    requires com.swirlds.base;
    requires com.hedera.node.hapi;

    provides ConfigurationExtension with
            GossipConfigurationExtension;
}
