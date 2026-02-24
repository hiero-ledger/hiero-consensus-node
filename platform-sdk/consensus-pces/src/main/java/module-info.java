// SPDX-License-Identifier: Apache-2.0
import com.swirlds.config.api.ConfigurationExtension;
import org.hiero.consensus.pces.config.PcesConfigurationExtension;

module org.hiero.consensus.pces {
    exports org.hiero.consensus.pces;
    exports org.hiero.consensus.pces.config;
    exports org.hiero.consensus.pces.actions;

    requires transitive com.swirlds.base;
    requires transitive com.swirlds.component.framework;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.metrics.api;
    requires transitive org.hiero.consensus.metrics;
    requires transitive org.hiero.consensus.model;
    requires transitive org.hiero.consensus.state;
    requires transitive org.hiero.consensus.utility;
    requires static transitive com.github.spotbugs.annotations;

    provides ConfigurationExtension with
            PcesConfigurationExtension;
}
