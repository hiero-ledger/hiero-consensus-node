// SPDX-License-Identifier: Apache-2.0
module org.hiero.consensus.hashgraph {
    requires transitive com.hedera.node.hapi;
    requires transitive com.swirlds.base;
    requires transitive com.swirlds.component.framework;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.metrics.api;
    requires transitive org.hiero.consensus.model;
    requires static com.github.spotbugs.annotations;

    exports org.hiero.consensus.hashgraph;
}
