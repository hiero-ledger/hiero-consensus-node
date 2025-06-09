// SPDX-License-Identifier: Apache-2.0
open module com.swirlds.common.test.fixtures {
    exports com.swirlds.common.test.fixtures;
    exports com.swirlds.common.test.fixtures.benchmark;
    exports com.swirlds.common.test.fixtures.dummy;
    exports com.swirlds.common.test.fixtures.io;
    exports com.swirlds.common.test.fixtures.io.extendable;
    exports com.swirlds.common.test.fixtures.map;
    exports com.swirlds.common.test.fixtures.merkle;
    exports com.swirlds.common.test.fixtures.merkle.dummy;
    exports com.swirlds.common.test.fixtures.merkle.util;
    exports com.swirlds.common.test.fixtures.threading;
    exports com.swirlds.common.test.fixtures.set;
    exports com.swirlds.common.test.fixtures.stream;
    exports com.swirlds.common.test.fixtures.fcqueue;
    exports com.swirlds.common.test.fixtures.platform;

    requires transitive com.hedera.pbj.runtime;
    requires transitive com.swirlds.base;
    requires transitive com.swirlds.common;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.metrics.api;
    requires transitive com.swirlds.virtualmap;
    requires transitive org.hiero.base.concurrent;
    requires transitive org.hiero.base.crypto;
    requires transitive org.hiero.base.utility;
    requires transitive org.hiero.consensus.model;
    requires com.swirlds.config.extensions.test.fixtures;
    requires com.swirlds.logging;
    requires com.swirlds.platform.core;
    requires org.hiero.base.utility.test.fixtures;
    requires org.apache.logging.log4j.core;
    requires org.apache.logging.log4j;
    requires org.junit.jupiter.api;
    requires org.mockito;
    requires static transitive com.github.spotbugs.annotations;
}
