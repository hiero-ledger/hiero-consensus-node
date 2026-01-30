// SPDX-License-Identifier: Apache-2.0
open module org.hiero.consensus.gui.test.fixtures {
    exports org.hiero.consensus.gui.test.fixtures;

    requires transitive com.hedera.node.hapi;
    requires transitive com.swirlds.common;
    requires transitive com.swirlds.platform.core.test.fixtures;
    requires transitive org.hiero.consensus.gui;
    requires transitive org.hiero.consensus.model;
    requires transitive java.desktop;
    requires com.swirlds.common.test.fixtures;
    requires com.swirlds.config.api;
    requires com.swirlds.platform.core;
    requires org.hiero.consensus.hashgraph.impl;
    requires org.hiero.consensus.utility;
    requires static transitive com.github.spotbugs.annotations;
}
