// SPDX-License-Identifier: Apache-2.0
import com.swirlds.config.api.ConfigurationExtension;
import org.hiero.consensus.event.intake.config.EventIntakeConfigurationExtension;

module org.hiero.consensus.event.intake {
    exports org.hiero.consensus.event.intake;
    exports org.hiero.consensus.event.intake.config;

    requires transitive com.swirlds.base;
    requires transitive com.swirlds.component.framework;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.metrics.api;
    requires transitive org.hiero.consensus.metrics;
    requires transitive org.hiero.consensus.model;
    requires transitive org.hiero.consensus.roster;
    requires transitive org.hiero.consensus.utility;
    requires static transitive com.github.spotbugs.annotations;

    provides ConfigurationExtension with
            EventIntakeConfigurationExtension;
}
