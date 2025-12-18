// SPDX-License-Identifier: Apache-2.0
module org.hiero.consensus.metrics {
    exports org.hiero.consensus.metrics;
    exports org.hiero.consensus.metrics.config;
    exports org.hiero.consensus.metrics.extensions;
    exports org.hiero.consensus.metrics.noop;
    exports org.hiero.consensus.metrics.noop.internal;
    exports org.hiero.consensus.metrics.platform;
    exports org.hiero.consensus.metrics.platform.prometheus;
    exports org.hiero.consensus.metrics.statistics;
    exports org.hiero.consensus.metrics.statistics.atomic;
    exports org.hiero.consensus.metrics.statistics.cycle;
    exports org.hiero.consensus.metrics.statistics.internal;
    exports org.hiero.consensus.metrics.statistics.simple;

    requires transitive com.swirlds.base;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.logging;
    requires transitive com.swirlds.metrics.api;
    requires transitive com.swirlds.metrics.impl;
    requires transitive org.hiero.base.concurrent;
    requires transitive org.hiero.base.utility;
    requires transitive org.hiero.consensus.concurrent;
    requires transitive org.hiero.consensus.model;
    requires transitive simpleclient.httpserver;
    requires transitive simpleclient;
    requires jdk.httpserver;
    requires org.apache.logging.log4j;
    requires static transitive com.github.spotbugs.annotations;
}
