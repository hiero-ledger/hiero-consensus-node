// SPDX-License-Identifier: Apache-2.0
import com.swirlds.config.api.ConfigurationExtension;
import org.hiero.consensus.state.config.StateConfigurationExtension;

module org.hiero.consensus.state {
    exports org.hiero.consensus.state.config;
    exports org.hiero.consensus.state.signed;
    exports org.hiero.consensus.state.snapshot;

    requires transitive com.hedera.node.hapi;
    requires transitive com.hedera.pbj.runtime;
    requires transitive com.swirlds.base;
    requires transitive com.swirlds.common;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.metrics.api;
    requires transitive com.swirlds.state.impl;
    requires transitive org.hiero.base.crypto;
    requires transitive org.hiero.base.utility;
    requires transitive org.hiero.consensus.model;
    requires transitive org.hiero.consensus.utility;
    requires com.swirlds.logging;
    requires com.swirlds.state.api;
    requires com.swirlds.virtualmap;
    requires org.hiero.base.concurrent;
    requires org.hiero.consensus.metrics;
    requires org.hiero.consensus.platformstate;
    requires org.hiero.consensus.roster;
    requires com.github.spotbugs.annotations;
    requires java.desktop;
    requires java.management;
    requires java.scripting;
    requires jdk.management;
    requires jdk.net;
    requires org.apache.logging.log4j;

    provides ConfigurationExtension with
            StateConfigurationExtension;
}
