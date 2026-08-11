// SPDX-License-Identifier: Apache-2.0
module org.hiero.consensus.fakes {
    exports org.hiero.consensus.fakes.noop;

    requires transitive com.swirlds.metrics.api;
    requires transitive org.hiero.consensus.metrics;
    requires transitive org.hiero.consensus.model;
    requires transitive org.hiero.consensus.roster;
    requires transitive org.hiero.consensus.utility;
    requires com.hedera.node.hapi;
    requires static transitive com.github.spotbugs.annotations;
}
