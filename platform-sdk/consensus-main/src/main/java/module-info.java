// SPDX-License-Identifier: Apache-2.0
module org.hiero.consensus.main {
    exports org.hiero.consensus.main;

    requires transitive org.hiero.consensus.main.model;
    requires com.hedera.node.hapi;
    requires com.swirlds.base;
    requires com.swirlds.config.api;
    requires org.hiero.consensus.utility;
    requires com.github.spotbugs.annotations;
}
