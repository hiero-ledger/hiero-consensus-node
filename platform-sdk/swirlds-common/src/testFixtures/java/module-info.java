// SPDX-License-Identifier: Apache-2.0
open module com.swirlds.common.test.fixtures {
    exports com.swirlds.common.test.fixtures;
    exports com.swirlds.common.test.fixtures.map;
    exports com.swirlds.common.test.fixtures.set;
    exports com.swirlds.common.test.fixtures.platform;

    requires transitive com.swirlds.base;
    requires transitive com.swirlds.common;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.metrics.api;
    requires transitive org.hiero.base.utility;
    requires transitive org.hiero.consensus.utility;
    requires org.hiero.base.concurrent;
    requires org.hiero.base.utility.test.fixtures;
    requires org.hiero.consensus.metrics;
    requires org.junit.jupiter.api;
    requires static transitive com.github.spotbugs.annotations;
}
