// SPDX-License-Identifier: Apache-2.0
import com.swirlds.platform.reconnect.ReconnectModule;
import org.hiero.consensus.gossip.impl.reconnect.ProtocolFactory;
import org.hiero.consensus.reconnect.impl.DefaultReconnectModule;
import org.hiero.consensus.reconnect.impl.ReconnectProtocolFactory;

module org.hiero.consensus.reconnect.impl {
    requires transitive com.hedera.node.hapi;
    requires transitive com.swirlds.base;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.metrics.api;
    requires transitive com.swirlds.platform.core;
    requires transitive com.swirlds.state.api;
    requires transitive com.swirlds.state.impl;
    requires transitive com.swirlds.virtualmap;
    requires transitive org.hiero.base.concurrent;
    requires transitive org.hiero.consensus.concurrent;
    requires transitive org.hiero.consensus.gossip.impl;
    requires transitive org.hiero.consensus.gossip;
    requires transitive org.hiero.consensus.model;
    requires transitive org.hiero.consensus.reconnect;
    requires transitive org.hiero.consensus.state;
    requires transitive org.hiero.consensus.utility;
    requires com.hedera.pbj.runtime;
    requires com.swirlds.common;
    requires com.swirlds.component.framework;
    requires com.swirlds.logging;
    requires org.hiero.base.crypto;
    requires org.hiero.base.utility;
    requires org.hiero.consensus.event.creator;
    requires org.hiero.consensus.event.intake;
    requires org.hiero.consensus.hashgraph;
    requires org.hiero.consensus.metrics;
    requires org.hiero.consensus.pces;
    requires org.hiero.consensus.platformstate;
    requires org.hiero.consensus.roster;
    requires com.github.spotbugs.annotations;
    requires java.management;
    requires java.scripting;
    requires jdk.management;
    requires jdk.net;
    requires org.apache.logging.log4j;

    provides ReconnectModule with
            DefaultReconnectModule;
    provides ProtocolFactory with
            ReconnectProtocolFactory;
}
