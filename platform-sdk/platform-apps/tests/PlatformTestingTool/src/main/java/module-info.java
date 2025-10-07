// SPDX-License-Identifier: Apache-2.0
module com.swirlds.demo.platform {
    exports com.swirlds.demo.platform;
    exports com.swirlds.demo.virtualmerkle.map.account;
    exports com.swirlds.demo.virtualmerkle.map.smartcontracts.bytecode;
    exports com.swirlds.demo.virtualmerkle.map.smartcontracts.data;
    exports com.swirlds.demo.merkle.map;
    exports com.swirlds.demo.platform.freeze;
    exports com.swirlds.demo.platform.iss;
    exports com.swirlds.demo.platform.nft;
    exports com.swirlds.demo.platform.nft.config;
    exports com.swirlds.demo.platform.actions;

    opens com.swirlds.demo.virtualmerkle;
    opens com.swirlds.demo.merkle.map.internal;

    exports com.swirlds.demo.platform.fs.stresstest.proto to
            com.google.protobuf,
            com.fasterxml.jackson.databind;

    opens com.swirlds.demo.platform;
    opens com.swirlds.demo.merkle.map;

    exports com.swirlds.demo.virtualmerkle.config to
            com.fasterxml.jackson.databind;

    requires com.hedera.node.hapi;
    requires com.hedera.pbj.runtime;
    requires com.swirlds.base;
    requires com.swirlds.common.test.fixtures;
    requires com.swirlds.common;
    requires com.swirlds.config.api;
    requires com.swirlds.config.extensions;
    requires com.swirlds.fchashmap;
    requires com.swirlds.fcqueue;
    requires com.swirlds.logging;
    requires com.swirlds.merkle.test.fixtures;
    requires com.swirlds.merkle;
    requires com.swirlds.merkledb;
    requires com.swirlds.metrics.api;
    requires com.swirlds.platform.core.test.fixtures;
    requires com.swirlds.platform.core;
    requires com.swirlds.state.api;
    requires com.swirlds.state.impl.test.fixtures;
    requires com.swirlds.virtualmap;
    requires org.hiero.base.concurrent;
    requires org.hiero.base.crypto.test.fixtures;
    requires org.hiero.base.crypto;
    requires org.hiero.base.utility;
    requires org.hiero.consensus.model;
    requires org.hiero.consensus.utility;
    requires com.fasterxml.jackson.annotation;
    requires com.fasterxml.jackson.core;
    requires com.fasterxml.jackson.databind;
    requires com.google.protobuf;
    requires java.management;
    requires org.apache.logging.log4j;
    requires static com.github.spotbugs.annotations;
}
