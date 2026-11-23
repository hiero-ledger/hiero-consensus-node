// SPDX-License-Identifier: Apache-2.0
module org.hiero.consensus.main {
    exports org.hiero.consensus.main;
    requires com.hedera.node.hapi;
    requires com.swirlds.base;
    requires com.swirlds.config.api;
    requires com.github.spotbugs.annotations;
    requires transitive org.hiero.consensus.main.model;
    requires org.hiero.consensus.utility;
}
