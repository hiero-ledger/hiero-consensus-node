// SPDX-License-Identifier: Apache-2.0
import com.swirlds.config.api.ConfigurationExtension;
import org.hiero.consensus.transaction.handling.config.TransactionHandlingConfigurationExtension;

module org.hiero.consensus.transaction.handling {
    exports org.hiero.consensus.transaction.handling.config;
    exports org.hiero.consensus.transaction.handling;

    requires transitive com.hedera.node.hapi;
    requires transitive com.swirlds.base;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.metrics.api;
    requires transitive com.swirlds.state.api;
    requires transitive com.swirlds.state.impl;
    requires transitive com.swirlds.virtualmap;
    requires transitive org.hiero.consensus.model;
    requires transitive org.hiero.consensus.state;
    requires transitive org.hiero.consensus.status.monitor;
    requires transitive org.hiero.consensus.wiring.framework;
    requires com.swirlds.logging;
    requires org.hiero.base.concurrent;
    requires org.hiero.base.crypto;
    requires org.hiero.consensus.event.stream;
    requires org.hiero.consensus.hashgraph;
    requires org.hiero.consensus.metrics;
    requires org.hiero.consensus.platformstate;
    requires org.apache.logging.log4j;
    requires static transitive com.github.spotbugs.annotations;

    provides ConfigurationExtension with
            TransactionHandlingConfigurationExtension;
}
