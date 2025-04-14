// SPDX-License-Identifier: Apache-2.0
/**
 * A disk-based VirtualDataSource implementation; complete module documentation to be assembled over time as the full
 * implementation is transplanted here.
 */
open module com.swirlds.merkledb {
    exports com.swirlds.merkledb;
    exports com.swirlds.merkledb.collections;
    exports com.swirlds.merkledb.config;
    exports com.swirlds.merkledb.files;
    exports com.swirlds.merkledb.files.hashmap;
    exports com.swirlds.merkledb.utilities;

    requires transitive com.hedera.pbj.runtime;
    requires transitive com.swirlds.common;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.metrics.api;
    requires transitive com.swirlds.virtualmap;
    requires transitive org.hiero.consensus.model;
    requires com.swirlds.base;
    requires com.swirlds.config.extensions;
    requires com.swirlds.logging;
    requires java.management;
    requires jdk.management;
    requires jdk.unsupported;
    requires org.apache.logging.log4j;
    requires org.eclipse.collections.api;
    requires org.eclipse.collections.impl;
    requires static transitive com.github.spotbugs.annotations;
}
