// SPDX-License-Identifier: Apache-2.0
module org.hiero.consensus.gui {
    exports org.hiero.consensus.gui.runner;

    // Transitive: types from these modules appear in the exported runner package's public API
    requires transitive com.hedera.node.hapi;
    requires transitive com.swirlds.common;
    requires transitive org.hiero.consensus.model;
    requires transitive org.hiero.consensus.utility;
    requires com.swirlds.base;
    requires com.swirlds.config.api;
    requires com.swirlds.logging;
    requires com.swirlds.metrics.api;
    requires org.hiero.base.crypto;
    requires org.hiero.consensus.hashgraph.impl;
    requires org.hiero.consensus.hashgraph;
    requires org.hiero.consensus.metrics;
    requires org.hiero.consensus.roster;
    requires java.desktop;
    requires org.apache.logging.log4j;
    requires static transitive com.github.spotbugs.annotations;
}
