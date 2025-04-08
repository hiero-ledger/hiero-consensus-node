// SPDX-License-Identifier: Apache-2.0
/**
 * A map that implements the FastCopyable interface.
 */
open module com.swirlds.merkle.test.fixtures {
    exports com.swirlds.merkle.test.fixtures;
    exports com.swirlds.merkle.test.fixtures.map.lifecycle;
    exports com.swirlds.merkle.test.fixtures.map.pta;
    exports com.swirlds.merkle.test.fixtures.map.util;

    requires transitive com.swirlds.common.test.fixtures;
    requires transitive com.swirlds.common;
    requires transitive com.swirlds.merkle;
    requires transitive org.hiero.base.utility;
    requires transitive org.hiero.consensus.model;
    requires transitive com.fasterxml.jackson.annotation;
    requires transitive com.fasterxml.jackson.core;
    requires transitive com.fasterxml.jackson.databind;
    requires com.hedera.pbj.runtime;
    requires com.swirlds.base;
    requires com.swirlds.fchashmap;
    requires com.swirlds.fcqueue;
    requires com.swirlds.logging;
    requires org.hiero.base.utility.test.fixtures;
    requires org.apache.logging.log4j.core;
    requires org.apache.logging.log4j;
}
