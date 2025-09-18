// SPDX-License-Identifier: Apache-2.0
module com.swirlds.state.impl {
    exports com.swirlds.state.merkle.disk;
    exports com.swirlds.state.merkle;

    // allow reflective access for tests
    opens com.swirlds.state.merkle.disk to
            com.hedera.node.app;

    requires transitive com.hedera.pbj.runtime;
    requires transitive com.swirlds.base;
    requires transitive com.swirlds.common;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.metrics.api;
    requires transitive com.swirlds.state.api;
    requires transitive com.swirlds.virtualmap;
    requires transitive org.hiero.base.crypto;
    requires transitive org.hiero.base.utility;
    requires com.hedera.node.hapi;
    requires com.swirlds.merkledb;
    requires org.apache.logging.log4j;
    requires org.json;
    requires static transitive com.github.spotbugs.annotations;
}
