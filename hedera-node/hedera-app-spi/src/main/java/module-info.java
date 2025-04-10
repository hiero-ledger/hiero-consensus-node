// SPDX-License-Identifier: Apache-2.0
module com.hedera.node.app.spi {
    requires transitive com.hedera.node.app.hapi.utils;
    requires transitive com.hedera.node.hapi;
    requires transitive com.hedera.pbj.runtime;
    requires transitive com.swirlds.common;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.metrics.api;
    requires transitive com.swirlds.state.api;
    requires transitive org.apache.logging.log4j;
    requires static transitive com.github.spotbugs.annotations;

    exports com.hedera.node.app.spi;
    exports com.hedera.node.app.spi.fees;
    exports com.hedera.node.app.spi.api;
    exports com.hedera.node.app.spi.ids;
    exports com.hedera.node.app.spi.key;
    exports com.hedera.node.app.spi.workflows;
    exports com.hedera.node.app.spi.records;
    exports com.hedera.node.app.spi.signatures;
    exports com.hedera.node.app.spi.store;
    exports com.hedera.node.app.spi.throttle;
    exports com.hedera.node.app.spi.validation;
    exports com.hedera.node.app.spi.workflows.record;
    exports com.hedera.node.app.spi.authorization;
    exports com.hedera.node.app.spi.metrics;
}
