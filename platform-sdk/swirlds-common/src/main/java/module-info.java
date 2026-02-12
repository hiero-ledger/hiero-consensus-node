// SPDX-License-Identifier: Apache-2.0
module com.swirlds.common {

    /* Exported packages. This list should remain alphabetized. */
    exports com.swirlds.common;
    exports com.swirlds.common.config;
    exports com.swirlds.common.context;
    exports com.swirlds.common.io.config;
    exports com.swirlds.common.io.exceptions;
    exports com.swirlds.common.io.filesystem;
    exports com.swirlds.common.io.streams;
    exports com.swirlds.common.io.utility;
    exports com.swirlds.common.merkle.synchronization;
    exports com.swirlds.common.merkle.synchronization.streams;
    exports com.swirlds.common.merkle.synchronization.task;
    exports com.swirlds.common.merkle.synchronization.utility;
    exports com.swirlds.common.merkle.synchronization.views;
    exports com.swirlds.common.merkle.utility;
    exports com.swirlds.common.notification;
    exports com.swirlds.common.platform;
    exports com.swirlds.common.stream;
    exports com.swirlds.common.stream.internal;
    exports com.swirlds.common.utility;
    exports com.swirlds.common.jackson;
    exports com.swirlds.common.startup;
    exports com.swirlds.common.merkle.synchronization.stats;
    exports com.swirlds.common.io.streams.internal to
            org.hiero.base.utility;

    opens com.swirlds.common.merkle.utility to
            com.fasterxml.jackson.databind;
    opens com.swirlds.common.utility to
            com.fasterxml.jackson.databind;
    opens com.swirlds.common.stream to
            com.fasterxml.jackson.databind;
    opens com.swirlds.common.io.utility to
            com.fasterxml.jackson.databind;
    opens com.swirlds.common.stream.internal to
            com.fasterxml.jackson.databind;

    requires transitive com.swirlds.base;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.metrics.api;
    requires transitive org.hiero.base.concurrent;
    requires transitive org.hiero.base.crypto;
    requires transitive org.hiero.base.utility;
    requires transitive org.hiero.consensus.concurrent;
    requires transitive org.hiero.consensus.model;
    requires transitive org.hiero.consensus.reconnect;
    requires transitive org.hiero.consensus.utility;
    requires transitive com.fasterxml.jackson.core;
    requires transitive com.fasterxml.jackson.databind;
    requires com.hedera.pbj.runtime;
    requires com.swirlds.logging;
    requires org.hiero.consensus.metrics;
    requires java.desktop;
    requires jdk.httpserver;
    requires jdk.management;
    requires org.apache.logging.log4j.core;
    requires org.apache.logging.log4j;
    requires org.bouncycastle.provider;
    requires static transitive com.github.spotbugs.annotations;
}
