// SPDX-License-Identifier: Apache-2.0
module org.hiero.consensus.main {
    exports org.hiero.consensus.main;

    requires transitive com.hedera.node.hapi;
    requires transitive com.swirlds.base;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.metrics.api;
    requires transitive org.hiero.base.utility;
    requires transitive org.hiero.consensus.main.model;
    requires transitive org.hiero.consensus.utility;
    requires com.github.spotbugs.annotations;
}
