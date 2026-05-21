// SPDX-License-Identifier: Apache-2.0
open module com.swirlds.virtualmap.test.fixtures {
    exports com.swirlds.virtualmap.test.fixtures;
    exports com.swirlds.virtualmap.test.fixtures.sync;

    requires transitive com.hedera.pbj.runtime;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.metrics.api;
    requires transitive org.hiero.base.crypto;
    requires transitive org.hiero.consensus.concurrent;
    requires transitive org.hiero.consensus.reconnect;
    requires com.swirlds.config.extensions.test.fixtures;
    requires com.swirlds.virtualmap;
    requires org.hiero.base.utility;
    requires org.hiero.consensus.metrics;
    requires org.hiero.consensus.model;
    requires org.hiero.consensus.utility;
    requires org.junit.jupiter.api;
    requires org.mockito;
}
