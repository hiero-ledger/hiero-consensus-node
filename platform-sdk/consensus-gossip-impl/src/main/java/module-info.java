// SPDX-License-Identifier: Apache-2.0
import org.hiero.consensus.gossip.GossipModule;
import org.hiero.consensus.gossip.impl.DefaultGossipModule;
import org.hiero.consensus.gossip.impl.reconnect.ProtocolFactory;

// SPDX-License-Identifier: Apache-2.0
module org.hiero.consensus.gossip.impl {
    exports org.hiero.consensus.gossip.impl.gossip to
            org.hiero.otter.fixtures;
    exports org.hiero.consensus.gossip.impl.gossip.shadowgraph to
            org.hiero.consensus.gossip.impl.test.fixtures;
    exports org.hiero.consensus.gossip.impl.gossip.sync to
            org.hiero.consensus.gossip.impl.test.fixtures,
            org.hiero.consensus.reconnect.impl;
    exports org.hiero.consensus.gossip.impl.network to
            org.hiero.consensus.gossip.impl.test.fixtures,
            org.hiero.consensus.reconnect.impl;
    exports org.hiero.consensus.gossip.impl.network.communication to
            org.hiero.consensus.gossip.impl.test.fixtures;
    exports org.hiero.consensus.gossip.impl.network.protocol to
            org.hiero.consensus.gossip.impl.test.fixtures,
            org.hiero.consensus.reconnect.impl;
    exports org.hiero.consensus.gossip.impl.network.topology to
            org.hiero.consensus.gossip.impl.test.fixtures;
    exports org.hiero.consensus.gossip.impl.reconnect to
            org.hiero.consensus.reconnect.impl;

    requires transitive com.hedera.node.hapi;
    requires transitive com.swirlds.base;
    requires transitive com.swirlds.component.framework;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.metrics.api;
    requires transitive com.swirlds.state.api;
    requires transitive com.swirlds.state.impl;
    requires transitive com.swirlds.virtualmap;
    requires transitive org.hiero.base.concurrent;
    requires transitive org.hiero.base.crypto;
    requires transitive org.hiero.base.utility;
    requires transitive org.hiero.consensus.concurrent;
    requires transitive org.hiero.consensus.gossip;
    requires transitive org.hiero.consensus.model;
    requires transitive org.hiero.consensus.state;
    requires transitive org.hiero.consensus.utility;
    requires transitive org.apache.logging.log4j;
    requires com.hedera.pbj.runtime;
    requires com.swirlds.logging;
    requires org.hiero.consensus.metrics;
    requires org.hiero.consensus.roster;
    requires static transitive com.github.spotbugs.annotations;

    provides GossipModule with
            DefaultGossipModule;

    uses ProtocolFactory;
}
