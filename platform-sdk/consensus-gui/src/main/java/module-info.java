// SPDX-License-Identifier: Apache-2.0
module org.hiero.consensus.gui {
    exports org.hiero.consensus.gui.runner;

    // Transitive: types from these modules appear in the exported runner package's public API
    requires transitive com.hedera.node.hapi;
    requires transitive com.swirlds.common;
    requires transitive org.hiero.consensus.model;
    requires transitive org.hiero.consensus.utility;
    requires com.swirlds.logging;
    requires org.hiero.consensus.hashgraph.impl;
    requires org.hiero.consensus.roster;
    requires java.desktop;
    requires org.apache.logging.log4j;
    requires static transitive com.github.spotbugs.annotations;
}
