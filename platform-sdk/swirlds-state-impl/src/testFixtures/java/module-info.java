// SPDX-License-Identifier: Apache-2.0
open module com.swirlds.state.impl.test.fixtures {
    requires transitive com.hedera.node.hapi;
    requires transitive com.hedera.pbj.runtime;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.state.api.test.fixtures;
    requires transitive com.swirlds.state.api;
    requires transitive com.swirlds.state.impl;
    requires transitive com.swirlds.virtualmap;
    requires transitive org.hiero.base.utility;
    requires transitive org.junit.jupiter.params;
    requires com.swirlds.common;
    requires com.swirlds.config.extensions;
    requires com.swirlds.merkledb.test.fixtures;
    requires com.swirlds.merkledb;
    requires com.swirlds.metrics.api;
    requires org.hiero.consensus.metrics;
    requires org.hiero.consensus.model;
    requires org.hiero.consensus.reconnect;
    requires org.junit.jupiter.api;
    requires static transitive com.github.spotbugs.annotations;

    exports com.swirlds.state.test.fixtures.merkle;
}
