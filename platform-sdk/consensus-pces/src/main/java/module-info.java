// SPDX-License-Identifier: Apache-2.0
import com.swirlds.config.api.ConfigurationExtension;
import org.hiero.consensus.pces.config.PcesConfigurationExtension;

module org.hiero.consensus.pces {
    exports org.hiero.consensus.pces.config;
    exports org.hiero.consensus.pces;

    requires transitive com.swirlds.base;
    requires transitive com.swirlds.component.framework;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.metrics.api;
    requires transitive org.hiero.base.utility;
    requires transitive org.hiero.consensus.metrics;
    requires transitive org.hiero.consensus.model;
    requires transitive org.hiero.consensus.utility;
    requires static transitive com.github.spotbugs.annotations;
    requires transitive org.hiero.consensus.event.intake;
    requires transitive org.hiero.consensus.event.creator;
    requires transitive org.hiero.consensus.hashgraph;

    provides ConfigurationExtension with
            PcesConfigurationExtension;
}
