// SPDX-License-Identifier: Apache-2.0
module org.hiero.consensus.gui {
    requires transitive com.hedera.node.hapi;
    requires transitive com.swirlds.common;
    requires transitive com.swirlds.config.api;
    requires transitive org.hiero.consensus.hashgraph.impl.test.fixtures;
    requires transitive org.hiero.consensus.hashgraph.impl;
    requires transitive org.hiero.consensus.metrics;
    requires transitive org.hiero.consensus.model;
    requires transitive java.desktop;
    requires com.swirlds.base;
    requires com.swirlds.common.test.fixtures;
    requires com.swirlds.logging;
    requires com.swirlds.metrics.api;
    requires org.hiero.base.crypto;
    requires org.hiero.consensus.gossip.impl;
    requires org.hiero.consensus.hashgraph;
    requires org.hiero.consensus.roster;
    requires org.hiero.consensus.utility;
    requires org.apache.logging.log4j;
    requires static com.github.spotbugs.annotations;

    exports org.hiero.consensus.gui;
    exports org.hiero.consensus.gui.components;
    exports org.hiero.consensus.gui.hashgraph;
    exports org.hiero.consensus.gui.hashgraph.internal;
    exports org.hiero.consensus.gui.runner;
}
