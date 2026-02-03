// SPDX-License-Identifier: Apache-2.0
import org.hiero.consensus.gossip.GossipModule;
import org.hiero.consensus.gossip.impl.GossipModuleImpl;

// SPDX-License-Identifier: Apache-2.0
module org.hiero.consensus.gossip.impl {
    exports org.hiero.consensus.gossip.impl.gossip;
    exports org.hiero.consensus.gossip.impl.gossip.permits;
    exports org.hiero.consensus.gossip.impl.gossip.rpc;
    exports org.hiero.consensus.gossip.impl.gossip.shadowgraph;
    exports org.hiero.consensus.gossip.impl.gossip.sync;
    exports org.hiero.consensus.gossip.impl.gossip.sync.protocol;
    exports org.hiero.consensus.gossip.impl.network;
    exports org.hiero.consensus.gossip.impl.network.communication;
    exports org.hiero.consensus.gossip.impl.network.communication.handshake;
    exports org.hiero.consensus.gossip.impl.network.communication.states;
    exports org.hiero.consensus.gossip.impl.network.connection;
    exports org.hiero.consensus.gossip.impl.network.connectivity;
    exports org.hiero.consensus.gossip.impl.network.protocol;
    exports org.hiero.consensus.gossip.impl.network.protocol.rpc;
    exports org.hiero.consensus.gossip.impl.network.topology;

    requires transitive com.hedera.node.hapi;
    requires transitive com.swirlds.base;
    requires transitive com.swirlds.component.framework;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.metrics.api;
    requires transitive org.hiero.base.concurrent;
    requires transitive org.hiero.base.crypto;
    requires transitive org.hiero.base.utility;
    requires transitive org.hiero.consensus.concurrent;
    requires transitive org.hiero.consensus.gossip;
    requires transitive org.hiero.consensus.model;
    requires transitive org.hiero.consensus.utility;
    requires transitive org.apache.logging.log4j;
    requires com.hedera.pbj.runtime;
    requires com.swirlds.logging;
    requires org.hiero.consensus.metrics;
    requires org.hiero.consensus.roster;
    requires static transitive com.github.spotbugs.annotations;

    provides GossipModule with
            GossipModuleImpl;
}
