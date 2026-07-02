// SPDX-License-Identifier: Apache-2.0
open module com.swirlds.virtualmap.test.fixtures {
    exports com.swirlds.virtualmap.test.fixtures;
    exports com.swirlds.virtualmap.test.fixtures.sync;
    exports com.swirlds.virtualmap.test.fixtures.datasource;

    requires transitive com.hedera.pbj.runtime;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.metrics.api;
    requires transitive org.hiero.base.crypto;
    requires com.swirlds.virtualmap;
    requires org.hiero.consensus.concurrent;
    requires org.hiero.consensus.metrics;
    requires org.hiero.consensus.model;
    requires org.junit.jupiter.api;
    requires org.mockito;
}
