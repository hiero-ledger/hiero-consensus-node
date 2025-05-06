// SPDX-License-Identifier: Apache-2.0
module com.hedera.node.app.spi.test.fixtures {
    exports com.hedera.node.app.spi.fixtures;
    exports com.hedera.node.app.spi.fixtures.workflows;
    exports com.hedera.node.app.spi.fixtures.util;
    exports com.hedera.node.app.spi.fixtures.info;

    requires transitive com.hedera.node.app.service.token; // TMP until FakePreHandleContext can be removed
    requires transitive com.hedera.node.app.spi;
    requires transitive com.hedera.node.hapi;
    requires transitive com.hedera.pbj.runtime;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.state.api.test.fixtures;
    requires transitive com.swirlds.state.api;
    requires transitive com.swirlds.state.impl.test.fixtures;
    requires transitive org.apache.logging.log4j;
    requires transitive org.assertj.core;
    requires transitive org.junit.jupiter.api;
    requires com.hedera.node.app.hapi.utils;
    requires com.swirlds.platform.core;
    requires org.hiero.base.utility;
    requires org.hiero.consensus.model;
    requires com.google.common;
    requires org.apache.logging.log4j.core;
    requires static transitive com.github.spotbugs.annotations;
}
