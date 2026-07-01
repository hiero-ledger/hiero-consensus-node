// SPDX-License-Identifier: Apache-2.0
module com.swirlds.merkledb.test.fixtures {
    exports com.swirlds.merkledb.test.fixtures;
    exports com.swirlds.merkledb.test.fixtures.files;

    requires transitive com.hedera.pbj.runtime;
    requires transitive com.swirlds.base;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.metrics.api;
    requires transitive com.swirlds.virtualmap;
    requires transitive org.hiero.base.crypto;
    requires transitive org.hiero.base.utility.test.fixtures;
    requires transitive org.hiero.base.utility;
    requires com.swirlds.merkledb;
    requires org.hiero.consensus.metrics;
    requires org.hiero.consensus.model;
    requires org.hiero.consensus.utility;
    requires java.management;
    requires org.junit.jupiter.api;
    requires org.mockito;

    opens com.swirlds.merkledb.test.fixtures to
            org.junit.platform.commons;
}
