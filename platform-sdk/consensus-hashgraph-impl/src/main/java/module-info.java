// SPDX-License-Identifier: Apache-2.0
module org.hiero.consensus.hashgraph.impl {
    exports org.hiero.consensus.hashgraph.impl.consensus;
    exports org.hiero.consensus.hashgraph.impl.linking;
    exports org.hiero.consensus.hashgraph.impl.metrics;
    exports org.hiero.consensus.hashgraph.impl;

    requires transitive com.hedera.node.hapi;
    requires transitive com.swirlds.base;
    requires transitive com.swirlds.component.framework;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.metrics.api;
    requires transitive org.hiero.consensus.hashgraph;
    requires transitive org.hiero.consensus.model;
    requires org.hiero.consensus.concurrent;
    requires org.hiero.consensus.roster;
    requires org.hiero.consensus.utility;
    requires static com.github.spotbugs.annotations;

    provides org.hiero.consensus.hashgraph.HashgraphModule with
            org.hiero.consensus.hashgraph.impl.DefaultHashgraphModule;
}
