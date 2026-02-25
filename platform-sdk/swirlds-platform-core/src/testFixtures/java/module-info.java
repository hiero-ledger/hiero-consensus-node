// SPDX-License-Identifier: Apache-2.0
open module com.swirlds.platform.core.test.fixtures {
    requires transitive com.hedera.node.hapi;
    requires transitive com.hedera.pbj.runtime;
    requires transitive com.swirlds.base;
    requires transitive com.swirlds.common.test.fixtures;
    requires transitive com.swirlds.common;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.config.extensions.test.fixtures;
    requires transitive com.swirlds.platform.core;
    requires transitive com.swirlds.state.api;
    requires transitive com.swirlds.state.impl;
    requires transitive org.hiero.base.crypto;
    requires transitive org.hiero.base.utility;
    requires transitive org.hiero.consensus.model;
    requires transitive org.hiero.consensus.platformstate;
    requires transitive org.hiero.consensus.state;
    requires transitive org.hiero.consensus.utility;
    requires com.swirlds.config.extensions;
    requires com.swirlds.logging;
    requires com.swirlds.merkledb.test.fixtures;
    requires com.swirlds.merkledb;
    requires com.swirlds.state.impl.test.fixtures;
    requires com.swirlds.virtualmap;
    requires org.hiero.base.crypto.test.fixtures;
    requires org.hiero.base.utility.test.fixtures;
    requires org.hiero.consensus.reconnect;
    requires org.hiero.consensus.roster.test.fixtures;
    requires org.hiero.consensus.utility.test.fixtures;
    requires com.github.spotbugs.annotations;
    requires org.junit.jupiter.api;
    requires org.mockito;

    exports com.swirlds.platform.test.fixtures;
    exports com.swirlds.platform.test.fixtures.config;
    exports com.swirlds.platform.test.fixtures.roster;
    exports com.swirlds.platform.test.fixtures.simulated;
    exports com.swirlds.platform.test.fixtures.stream;
    exports com.swirlds.platform.test.fixtures.utils;
    exports com.swirlds.platform.test.fixtures.resource;
    exports com.swirlds.platform.test.fixtures.state;
    exports com.swirlds.platform.test.fixtures.state.manager;
}
