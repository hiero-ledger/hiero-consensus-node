// SPDX-License-Identifier: Apache-2.0
module org.hiero.consensus.hashgraph.impl {
    requires transitive com.hedera.node.hapi;
    requires transitive com.swirlds.base;
    requires transitive com.swirlds.component.framework;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.metrics.api;
    requires transitive org.hiero.consensus.hashgraph;
    requires transitive org.hiero.consensus.model;
    requires static com.github.spotbugs.annotations; requires org.hiero.consensus.utility; requires org.hiero.consensus.concurrent; requires org.hiero.consensus.roster;

    provides org.hiero.consensus.hashgraph.HashgraphModule with
            org.hiero.consensus.hashgraph.impl.DefaultHashgraphModule;
}
