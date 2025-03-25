// SPDX-License-Identifier: Apache-2.0
module com.swirlds.merkledb.test.fixtures {
    exports com.swirlds.merkledb.test.fixtures;

    requires transitive com.hedera.pbj.runtime;
    requires transitive com.swirlds.common;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.metrics.api;
    requires transitive com.swirlds.virtualmap;
    requires transitive org.hiero.consensus.model;
    requires transitive org.apache.logging.log4j.core;
    requires com.swirlds.base;
    requires com.swirlds.config.extensions.test.fixtures;
    requires com.swirlds.merkledb;
    requires java.management;
    requires jdk.management;
    requires org.apache.logging.log4j;
    requires org.junit.jupiter.api;
    requires org.mockito;
}
