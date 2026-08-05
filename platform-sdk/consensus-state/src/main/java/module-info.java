// SPDX-License-Identifier: Apache-2.0
import com.swirlds.config.api.ConfigurationExtension;
import org.hiero.consensus.state.config.StateConfigurationExtension;

module org.hiero.consensus.state {
    exports org.hiero.consensus.state.config;
    exports org.hiero.consensus.state.nexus;
    exports org.hiero.consensus.state.saved;
    exports org.hiero.consensus.state.signed;
    exports org.hiero.consensus.state.snapshot;
    exports org.hiero.consensus.state;
    exports org.hiero.consensus.state.persistence to
            com.swirlds.platform.core,
            org.hiero.consensus.reconnect.impl,
            org.hiero.consensus.pcli;

    requires transitive com.hedera.node.hapi;
    requires transitive com.hedera.pbj.runtime;
    requires transitive com.swirlds.base;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.metrics.api;
    requires transitive com.swirlds.state.api;
    requires transitive com.swirlds.state.impl;
    requires transitive com.swirlds.virtualmap;
    requires transitive org.hiero.base.crypto;
    requires transitive org.hiero.base.utility;
    requires transitive org.hiero.consensus.metrics;
    requires transitive org.hiero.consensus.model;
    requires transitive org.hiero.consensus.wiring.framework;
    requires com.swirlds.logging;
    requires org.hiero.base.concurrent;
    requires org.hiero.consensus.concurrent;
    requires org.hiero.consensus.pces.impl;
    requires org.hiero.consensus.pces;
    requires org.hiero.consensus.platformstate;
    requires org.hiero.consensus.roster;
    requires org.hiero.consensus.utility;
    requires org.apache.logging.log4j;
    requires static transitive com.github.spotbugs.annotations;

    provides ConfigurationExtension with
            StateConfigurationExtension;
}
