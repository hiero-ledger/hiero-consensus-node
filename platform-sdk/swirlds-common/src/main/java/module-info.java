// SPDX-License-Identifier: Apache-2.0
module com.swirlds.common {

    /* Exported packages. This list should remain alphabetized. */
    exports com.swirlds.common;
    exports com.swirlds.common.config;
    exports com.swirlds.common.context;
    exports com.swirlds.common.formatting;
    exports com.swirlds.common.io;
    exports com.swirlds.common.io.config;
    exports com.swirlds.common.io.exceptions;
    exports com.swirlds.common.io.extendable;
    exports com.swirlds.common.io.extendable.extensions;
    exports com.swirlds.common.io.filesystem;
    exports com.swirlds.common.io.streams;
    exports com.swirlds.common.io.utility;
    exports com.swirlds.common.merkle;
    exports com.swirlds.common.merkle.copy;
    exports com.swirlds.common.merkle.crypto;
    exports com.swirlds.common.merkle.exceptions;
    exports com.swirlds.common.merkle.hash;
    exports com.swirlds.common.merkle.impl;
    exports com.swirlds.common.merkle.impl.destroyable;
    exports com.swirlds.common.merkle.impl.internal;
    exports com.swirlds.common.merkle.interfaces;
    exports com.swirlds.common.merkle.iterators;
    exports com.swirlds.common.merkle.route;
    exports com.swirlds.common.merkle.synchronization;
    exports com.swirlds.common.merkle.synchronization.config;
    exports com.swirlds.common.merkle.synchronization.streams;
    exports com.swirlds.common.merkle.synchronization.task;
    exports com.swirlds.common.merkle.synchronization.utility;
    exports com.swirlds.common.merkle.synchronization.views;
    exports com.swirlds.common.merkle.utility;
    exports com.swirlds.common.metrics;
    exports com.swirlds.common.metrics.config;
    exports com.swirlds.common.metrics.noop;
    exports com.swirlds.common.metrics.platform;
    exports com.swirlds.common.metrics.platform.prometheus;
    exports com.swirlds.common.notification;
    exports com.swirlds.common.platform;
    exports com.swirlds.common.stream;
    exports com.swirlds.common.stream.internal;
    exports com.swirlds.common.threading.framework;
    exports com.swirlds.common.threading.framework.config;
    exports com.swirlds.common.threading.manager;
    exports com.swirlds.common.threading.pool;
    exports com.swirlds.common.time;
    exports com.swirlds.common.utility;
    exports com.swirlds.common.utility.throttle;
    exports com.swirlds.common.jackson;
    exports com.swirlds.common.units;
    exports com.swirlds.common.metrics.extensions;
    exports com.swirlds.common.units.internal;
    exports com.swirlds.common.metrics.statistics;
    exports com.swirlds.common.metrics.statistics.internal to
            com.swirlds.demo.platform,
            com.swirlds.platform.core;
    exports com.swirlds.common.startup;
    exports com.swirlds.common.merkle.synchronization.stats;
    exports com.swirlds.common.io.streams.internal to
            org.hiero.base.utility;
    exports com.swirlds.common.merkle.route.internal to
            org.hiero.base.utility;

    opens com.swirlds.common.units.internal to
            com.fasterxml.jackson.databind;
    opens com.swirlds.common.merkle.utility to
            com.fasterxml.jackson.databind;
    opens com.swirlds.common.utility to
            com.fasterxml.jackson.databind;
    opens com.swirlds.common.utility.throttle to
            com.fasterxml.jackson.databind;
    opens com.swirlds.common.stream to
            com.fasterxml.jackson.databind;
    opens com.swirlds.common.merkle.copy to
            com.fasterxml.jackson.databind;
    opens com.swirlds.common.merkle.impl to
            com.fasterxml.jackson.databind;
    opens com.swirlds.common.merkle.impl.internal to
            com.fasterxml.jackson.databind;
    opens com.swirlds.common.merkle.impl.destroyable to
            com.fasterxml.jackson.databind;
    opens com.swirlds.common.io.utility to
            com.fasterxml.jackson.databind;
    opens com.swirlds.common.stream.internal to
            com.fasterxml.jackson.databind;
    opens com.swirlds.common.merkle.crypto to
            com.fasterxml.jackson.databind;
    opens com.swirlds.common.formatting to
            com.fasterxml.jackson.databind;
    opens com.swirlds.common.units to
            com.fasterxml.jackson.databind;

    requires transitive com.swirlds.base;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.metrics.api;
    requires transitive com.swirlds.metrics.impl;
    requires transitive org.hiero.base.concurrent;
    requires transitive org.hiero.base.crypto;
    requires transitive org.hiero.base.utility;
    requires transitive org.hiero.consensus.model;
    requires transitive com.fasterxml.jackson.core;
    requires transitive com.fasterxml.jackson.databind;
    requires transitive org.apache.logging.log4j;
    requires transitive simpleclient;
    requires com.hedera.pbj.runtime;
    requires com.swirlds.logging;
    requires java.desktop;
    requires jdk.httpserver;
    requires jdk.management;
    requires org.apache.logging.log4j.core;
    requires org.bouncycastle.provider;
    requires simpleclient.httpserver;
    requires static transitive com.github.spotbugs.annotations;
}
