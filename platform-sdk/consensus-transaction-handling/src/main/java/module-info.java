import com.swirlds.config.api.ConfigurationExtension;
import org.hiero.consensus.transaction.handling.config.TransactionHandlingConfigurationExtension;

// SPDX-License-Identifier: Apache-2.0
module org.hiero.consensus.transaction.handling {
    exports org.hiero.consensus.transaction.handling;
    exports org.hiero.consensus.transaction.handling.config;
    exports org.hiero.consensus.transaction.handling.internal;

    requires transitive com.hedera.node.hapi;
    requires transitive com.hedera.pbj.runtime;
    requires transitive com.swirlds.base;
    requires transitive com.swirlds.common;
    requires transitive com.swirlds.component.framework;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.metrics.api;
    requires transitive com.swirlds.state.api;
    requires transitive com.swirlds.state.impl;
    requires transitive com.swirlds.virtualmap;
    requires transitive org.hiero.base.concurrent;
    requires transitive org.hiero.base.crypto;
    requires transitive org.hiero.base.utility;
    requires transitive org.hiero.consensus.event.creator;
    requires transitive org.hiero.consensus.event.intake;
    requires transitive org.hiero.consensus.event.stream;
    requires transitive org.hiero.consensus.gossip;
    requires transitive org.hiero.consensus.hashgraph;
    requires transitive org.hiero.consensus.metrics;
    requires transitive org.hiero.consensus.model;
    requires transitive org.hiero.consensus.pces;
    requires transitive org.hiero.consensus.roster;
    requires transitive org.hiero.consensus.state;
    requires transitive org.hiero.consensus.utility;
    requires com.swirlds.config.extensions;
    requires com.swirlds.logging;
    requires com.swirlds.merkledb;
    requires org.hiero.consensus.concurrent;
    requires org.hiero.consensus.pces.impl;
    requires org.hiero.consensus.platformstate;
    requires com.github.spotbugs.annotations;
    requires java.management;
    requires java.scripting;
    requires jdk.management;
    requires jdk.net;
    requires org.apache.logging.log4j;
    requires org.bouncycastle.pkix;
    requires org.bouncycastle.provider;

    provides ConfigurationExtension with
            TransactionHandlingConfigurationExtension;
}
