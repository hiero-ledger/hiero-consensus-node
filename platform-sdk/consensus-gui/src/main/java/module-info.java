// SPDX-License-Identifier: Apache-2.0
module org.hiero.consensus.gui {
    exports org.hiero.consensus.gui.api;

    // Transitive: types from these modules appear in the exported runner package's public API
    requires transitive com.hedera.node.hapi;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.metrics.api;
    requires transitive org.hiero.consensus.model;
    requires transitive org.hiero.consensus.utility;
    requires com.swirlds.base;
    requires com.swirlds.logging;
    requires org.hiero.base.crypto;
    requires org.hiero.consensus.hashgraph.impl;
    requires org.hiero.consensus.hashgraph;
    requires org.hiero.consensus.roster;
    requires java.desktop;
    requires org.apache.logging.log4j;
    requires static transitive com.github.spotbugs.annotations;
}
