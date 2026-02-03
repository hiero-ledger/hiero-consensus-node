// SPDX-License-Identifier: Apache-2.0
/**
 * A map that implements the FastCopyable interface.
 */
open module com.swirlds.virtualmap {
    exports com.swirlds.virtualmap;
    exports com.swirlds.virtualmap.datasource;
    // Currently, exported only for tests.
    exports com.swirlds.virtualmap.internal.merkle;
    exports com.swirlds.virtualmap.config;

    // Testing-only exports
    exports com.swirlds.virtualmap.internal to
            com.swirlds.merkle,
            com.swirlds.merkledb,
            com.swirlds.merkledb.test.fixtures,
            com.swirlds.virtualmap.test.fixtures,
            com.swirlds.platform.core,
            com.swirlds.state.impl,
            com.hedera.state.validator,
            com.hedera.node.app;
    exports com.swirlds.virtualmap.internal.pipeline to
            com.swirlds.merkle,
            com.swirlds.merkledb;
    exports com.swirlds.virtualmap.internal.cache to
            com.swirlds.merkledb,
            com.swirlds.virtualmap.test.fixtures,
            com.swirlds.platform.core.test.fixtures,
            com.hedera.state.validator;
    exports com.swirlds.virtualmap.internal.reconnect to
            com.hedera.state.validator;
    exports com.swirlds.virtualmap.internal.hash to
            com.hedera.state.validator;

    requires transitive com.hedera.pbj.runtime;
    requires transitive com.swirlds.base;
    requires transitive com.swirlds.common;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.metrics.api;
    requires transitive org.hiero.base.concurrent;
    requires transitive org.hiero.base.crypto;
    requires transitive org.hiero.base.utility;
    requires transitive org.hiero.consensus.concurrent;
    requires transitive org.hiero.consensus.reconnect;
    requires com.swirlds.logging;
    requires java.management; // Test dependency
    requires org.apache.logging.log4j;
    requires static transitive com.github.spotbugs.annotations;
}
