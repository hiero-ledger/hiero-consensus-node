// SPDX-License-Identifier: Apache-2.0
import com.swirlds.config.api.ConfigurationExtension;
import org.hiero.consensus.status.monitor.config.StatusMonitorConfigurationExtension;

module org.hiero.consensus.status.monitor {
    exports org.hiero.consensus.status.monitor.actions;
    exports org.hiero.consensus.status.monitor.config;
    exports org.hiero.consensus.status.monitor;

    requires transitive com.swirlds.base;
    requires transitive com.swirlds.component.framework;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.metrics.api;
    requires transitive org.hiero.consensus.model;
    requires com.hedera.node.hapi;
    requires com.swirlds.logging;
    requires org.hiero.base.utility;
    requires org.hiero.consensus.metrics;
    requires org.hiero.consensus.roster;
    requires org.apache.logging.log4j;
    requires static transitive com.github.spotbugs.annotations;

    provides ConfigurationExtension with
            StatusMonitorConfigurationExtension;
}
