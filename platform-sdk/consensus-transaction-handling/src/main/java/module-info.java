// SPDX-License-Identifier: Apache-2.0
import com.swirlds.config.api.ConfigurationExtension;
import org.hiero.consensus.transaction.handling.config.TransactionHandlingConfigurationExtension;

module org.hiero.consensus.transaction.handling {
    exports org.hiero.consensus.transaction.handling;
    exports org.hiero.consensus.transaction.handling.config;

    requires transitive com.hedera.node.hapi;
    requires transitive com.swirlds.base;
    requires transitive com.swirlds.component.framework;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.metrics.api;
    requires transitive com.swirlds.state.api;
    requires transitive com.swirlds.state.impl;
    requires transitive com.swirlds.virtualmap;
    requires transitive org.hiero.consensus.model;
    requires transitive org.hiero.consensus.state;
    requires transitive org.hiero.consensus.utility;
    requires com.swirlds.logging;
    requires org.hiero.base.concurrent;
    requires org.hiero.base.crypto;
    requires org.hiero.consensus.event.stream;
    requires org.hiero.consensus.hashgraph;
    requires org.hiero.consensus.metrics;
    requires org.hiero.consensus.platformstate;
    requires com.github.spotbugs.annotations;
    requires java.management;
    requires java.scripting;
    requires jdk.management;
    requires jdk.net;
    requires org.apache.logging.log4j;

    provides ConfigurationExtension with
            TransactionHandlingConfigurationExtension;
}
