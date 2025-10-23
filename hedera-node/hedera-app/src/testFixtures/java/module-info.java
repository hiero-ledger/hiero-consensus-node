// SPDX-License-Identifier: Apache-2.0
open module com.hedera.node.app.test.fixtures {
    exports com.hedera.node.app.fixtures;
    exports com.hedera.node.app.fixtures.signature;
    exports com.hedera.node.app.fixtures.state;

    requires transitive com.hedera.node.app.spi.test.fixtures;
    requires transitive com.hedera.node.app.spi;
    requires transitive com.hedera.node.app;
    requires transitive com.hedera.node.config;
    requires transitive com.hedera.node.hapi;
    requires transitive com.hedera.pbj.runtime;
    requires transitive com.swirlds.common;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.metrics.api;
    requires transitive com.swirlds.platform.core;
    requires transitive com.swirlds.state.api.test.fixtures;
    requires transitive com.swirlds.state.api;
    requires transitive org.hiero.base.crypto;
    requires transitive org.hiero.base.utility;
    requires transitive org.hiero.consensus.model;
    requires com.hedera.node.app.hapi.utils;
    requires com.hedera.node.app.service.entityid.impl;
    requires com.hedera.node.app.service.entityid;
    requires com.hedera.node.app.service.token;
    requires com.hedera.node.config.test.fixtures;
    requires com.swirlds.base;
    requires com.swirlds.config.extensions.test.fixtures;
    requires com.swirlds.merkledb.test.fixtures;
    requires com.swirlds.platform.core.test.fixtures;
    requires com.swirlds.state.impl.test.fixtures;
    requires org.hiero.consensus.utility;
    requires org.apache.logging.log4j;
    requires org.assertj.core;
    requires org.junit.jupiter.api;
}
