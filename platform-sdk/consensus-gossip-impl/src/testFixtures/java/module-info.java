// SPDX-License-Identifier: Apache-2.0
open module org.hiero.consensus.gossip.impl.test.fixtures {
    exports org.hiero.consensus.gossip.impl.test.fixtures.communication;
    exports org.hiero.consensus.gossip.impl.test.fixtures.communication.multithreaded;
    exports org.hiero.consensus.gossip.impl.test.fixtures.network;
    exports org.hiero.consensus.gossip.impl.test.fixtures.sync;

    requires transitive com.hedera.node.hapi;
    requires transitive com.hedera.pbj.runtime;
    requires transitive com.swirlds.base;
    requires transitive com.swirlds.common.test.fixtures;
    requires transitive com.swirlds.common;
    requires transitive com.swirlds.component.framework;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.config.extensions.test.fixtures;
    requires transitive com.swirlds.platform.core;
    requires transitive com.swirlds.state.api;
    requires transitive org.hiero.base.crypto;
    requires transitive org.hiero.base.utility;
    requires transitive org.hiero.consensus.gossip.impl;
    requires transitive org.hiero.consensus.hashgraph.impl;
    requires transitive org.hiero.consensus.hashgraph;
    requires transitive org.hiero.consensus.model.test.fixtures;
    requires transitive org.hiero.consensus.model;
    requires transitive org.hiero.consensus.utility.test.fixtures;
    requires transitive org.hiero.consensus.utility;
    requires transitive org.assertj.core;
    requires com.swirlds.base.test.fixtures;
    requires com.swirlds.config.extensions;
    requires com.swirlds.logging;
    requires com.swirlds.merkledb.test.fixtures;
    requires com.swirlds.merkledb;
    requires com.swirlds.metrics.api;
    requires com.swirlds.state.impl.test.fixtures;
    requires com.swirlds.state.impl;
    requires com.swirlds.virtualmap;
    requires org.hiero.base.crypto.test.fixtures;
    requires org.hiero.base.utility.test.fixtures;
    requires org.hiero.consensus.metrics;
    requires org.hiero.consensus.reconnect;
    requires com.github.spotbugs.annotations;
    requires java.desktop;
    requires org.junit.jupiter.api;
    requires org.mockito;
}
