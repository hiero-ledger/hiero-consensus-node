// SPDX-License-Identifier: Apache-2.0
open module org.hiero.otter.test {
    requires com.swirlds.platform.core;
    requires org.hiero.base.crypto;
    requires org.hiero.consensus.event.creator;
    requires org.hiero.consensus.hashgraph;
    requires org.hiero.consensus.model;
    requires org.hiero.consensus.reconnect;
    requires org.hiero.consensus.state;
    requires org.hiero.otter.fixtures;
    requires org.junit.jupiter.params;
    requires static com.github.spotbugs.annotations;
}
