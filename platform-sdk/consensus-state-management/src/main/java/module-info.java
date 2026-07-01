// SPDX-License-Identifier: Apache-2.0
import com.swirlds.config.api.ConfigurationExtension;
import org.hiero.consensus.state.management.config.StateManagementConfigurationExtension;

module org.hiero.consensus.state.management {
    exports org.hiero.consensus.state.management.config;
    exports org.hiero.consensus.state.management.persistence to
            com.swirlds.platform.core,
            org.hiero.consensus.reconnect.impl,
            org.hiero.consensus.pcli;
    exports org.hiero.consensus.state.management;

    requires transitive com.hedera.node.hapi;
    requires transitive com.hedera.pbj.runtime;
    requires transitive com.swirlds.base;
    requires transitive com.swirlds.component.framework;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.metrics.api;
    requires transitive com.swirlds.state.api;
    requires transitive com.swirlds.state.impl;
    requires transitive com.swirlds.virtualmap;
    requires transitive org.hiero.base.utility;
    requires transitive org.hiero.consensus.metrics;
    requires transitive org.hiero.consensus.model;
    requires transitive org.hiero.consensus.state;
    requires com.swirlds.logging;
    requires org.hiero.base.crypto;
    requires org.hiero.consensus.pces.impl;
    requires org.hiero.consensus.pces;
    requires org.hiero.consensus.platformstate;
    requires org.hiero.consensus.roster;
    requires org.hiero.consensus.utility;
    requires com.github.spotbugs.annotations;
    requires java.management;
    requires java.scripting;
    requires jdk.management;
    requires jdk.net;
    requires org.apache.logging.log4j;

    provides ConfigurationExtension with
            StateManagementConfigurationExtension;
}
