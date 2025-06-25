// SPDX-License-Identifier: Apache-2.0
/**
 * A map that implements the FastCopyable interface.
 */
open module com.swirlds.merkle.test.fixtures {
    exports com.swirlds.merkle.test.fixtures;
    exports com.swirlds.merkle.test.fixtures.map.benchmark;
    exports com.swirlds.merkle.test.fixtures.map.benchmark.operations;
    exports com.swirlds.merkle.test.fixtures.map.dummy;
    exports com.swirlds.merkle.test.fixtures.map.lifecycle;
    exports com.swirlds.merkle.test.fixtures.map.pta;
    exports com.swirlds.merkle.test.fixtures.map.util;

    requires transitive com.swirlds.common.test.fixtures;
    requires transitive com.swirlds.common;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.fchashmap;
    requires transitive com.swirlds.fcqueue;
    requires transitive com.swirlds.merkle;
    requires transitive org.hiero.base.crypto;
    requires transitive org.hiero.base.utility;
    requires transitive com.fasterxml.jackson.annotation;
    requires transitive com.fasterxml.jackson.core;
    requires transitive com.fasterxml.jackson.databind;
    requires com.hedera.pbj.runtime;
    requires com.swirlds.base;
    requires com.swirlds.logging;
    requires com.swirlds.merkledb;
    requires com.swirlds.virtualmap;
    requires org.hiero.base.utility.test.fixtures;
    requires org.apache.logging.log4j.core;
    requires org.apache.logging.log4j;
}
