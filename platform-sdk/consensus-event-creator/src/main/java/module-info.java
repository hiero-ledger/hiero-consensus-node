// SPDX-License-Identifier: Apache-2.0
import com.swirlds.config.api.ConfigurationExtension;
import org.hiero.consensus.event.creator.config.EventCreatorConfigurationExtension;

module org.hiero.consensus.event.creator {
    exports org.hiero.consensus.event.creator;
    exports org.hiero.consensus.event.creator.config;

    requires transitive com.hedera.node.hapi;
    requires transitive com.swirlds.base;
    requires transitive com.swirlds.component.framework;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.metrics.api;
    requires transitive org.hiero.consensus.model;
    requires static transitive com.github.spotbugs.annotations;

    provides ConfigurationExtension with
            EventCreatorConfigurationExtension;
}
