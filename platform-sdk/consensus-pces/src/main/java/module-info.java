// SPDX-License-Identifier: Apache-2.0
import com.swirlds.config.api.ConfigurationExtension;
import org.hiero.consensus.pces.config.PcesConfigurationExtension;

module org.hiero.consensus.pces {
    exports org.hiero.consensus.pces;
    exports org.hiero.consensus.pces.config;

    requires transitive com.swirlds.config.api;
    requires static transitive com.github.spotbugs.annotations;
    requires static transitive com.google.auto.service;

    provides ConfigurationExtension with
            PcesConfigurationExtension;
}
