// SPDX-License-Identifier: Apache-2.0
open module com.swirlds.state.impl.test.fixtures {
    requires transitive com.hedera.node.hapi;
    requires transitive com.hedera.pbj.runtime;
    requires transitive com.swirlds.base;
    requires transitive com.swirlds.common;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.merkle;
    requires transitive com.swirlds.metrics.api;
    requires transitive com.swirlds.state.api.test.fixtures;
    requires transitive com.swirlds.state.api;
    requires transitive com.swirlds.state.impl;
    requires transitive com.swirlds.virtualmap;
    requires transitive org.hiero.base.utility;
    requires transitive org.junit.jupiter.api;
    requires transitive org.junit.jupiter.params;
    requires com.swirlds.common.test.fixtures;
    requires com.swirlds.fcqueue;
    requires com.swirlds.logging;
    requires com.swirlds.merkledb.test.fixtures;
    requires com.swirlds.merkledb;
    requires org.hiero.base.crypto;
    requires org.mockito;
    requires static transitive com.github.spotbugs.annotations;

    exports com.swirlds.state.test.fixtures.merkle;
    exports com.swirlds.state.test.fixtures.merkle.disk;
    exports com.swirlds.state.test.fixtures.merkle.singleton;
    exports com.swirlds.state.test.fixtures.merkle.memory;
    exports com.swirlds.state.test.fixtures.merkle.queue;
}
