// SPDX-License-Identifier: Apache-2.0
module org.hiero.consensus.gui {
    exports org.hiero.consensus.gui to
            org.hiero.consensus.gui.test.fixtures,
            com.swirlds.platform.core.test.fixtures;
    exports org.hiero.consensus.gui.components to
            org.hiero.consensus.gui.test.fixtures;
    exports org.hiero.consensus.gui.model to
            org.hiero.consensus.gui.test.fixtures;
    exports org.hiero.consensus.gui.internal to
            org.hiero.consensus.gui.test.fixtures;
    exports org.hiero.consensus.gui.hashgraph to
            org.hiero.consensus.gui.test.fixtures,
            com.swirlds.platform.core.test.fixtures;
    exports org.hiero.consensus.gui.hashgraph.internal to
            org.hiero.consensus.gui.test.fixtures,
            com.swirlds.platform.core.test.fixtures;

    requires transitive com.hedera.node.hapi;
    requires transitive com.swirlds.common;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.metrics.api;
    requires transitive com.swirlds.platform.core;
    requires transitive org.hiero.consensus.hashgraph.impl;
    requires transitive org.hiero.consensus.metrics;
    requires transitive org.hiero.consensus.model;
    requires com.swirlds.base;
    requires com.swirlds.logging;
    requires org.hiero.base.crypto;
    requires org.hiero.consensus.hashgraph;
    requires org.hiero.consensus.roster;
    requires org.hiero.consensus.utility;
    requires com.github.spotbugs.annotations;
    requires java.desktop;
    requires org.apache.logging.log4j;
}
