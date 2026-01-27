// SPDX-License-Identifier: Apache-2.0
import org.hiero.consensus.hashgraph.HashgraphModule;
import org.hiero.consensus.hashgraph.impl.DefaultHashgraphModule;

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
    requires transitive org.hiero.base.crypto;
    requires transitive org.hiero.base.utility;
    requires transitive org.hiero.consensus.hashgraph;
    requires transitive org.hiero.consensus.metrics;
    requires transitive org.hiero.consensus.model;
    requires com.hedera.pbj.runtime;
    requires com.swirlds.logging;
    requires org.hiero.consensus.concurrent;
    requires org.hiero.consensus.roster;
    requires org.hiero.consensus.utility;
    requires org.apache.logging.log4j;
    requires static com.github.spotbugs.annotations;

    provides HashgraphModule with
            DefaultHashgraphModule;
}
