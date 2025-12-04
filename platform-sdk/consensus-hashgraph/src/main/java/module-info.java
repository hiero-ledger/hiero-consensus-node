// SPDX-License-Identifier: Apache-2.0
module org.hiero.consensus.hashgraph {
    requires transitive com.swirlds.config.api;
    requires com.hedera.node.hapi;
    requires com.swirlds.component.framework;
    requires com.swirlds.metrics.api;
    requires org.hiero.consensus.model;
    requires com.github.spotbugs.annotations;
    requires java.sql;

    exports org.hiero.consensus.hashgraph;

//    requires transitive com.hedera.node.hapi;
//    requires transitive com.swirlds.base;
//    requires transitive com.swirlds.config.api;
//    requires transitive com.swirlds.metrics.api;
//    requires transitive org.hiero.base.crypto;
//    requires transitive org.hiero.consensus.model;
//    requires static transitive com.github.spotbugs.annotations;
}
