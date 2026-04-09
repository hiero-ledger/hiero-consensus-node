// SPDX-License-Identifier: Apache-2.0
open module com.swirlds.virtualmap.test.fixtures {
    exports com.swirlds.virtualmap.test.fixtures;

    requires transitive com.hedera.pbj.runtime;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.metrics.api;
    requires transitive org.hiero.base.crypto;
    requires com.swirlds.common;
    requires com.swirlds.virtualmap;
    requires org.hiero.base.utility;
    requires org.hiero.consensus.reconnect;
    requires org.junit.jupiter.api;
}
