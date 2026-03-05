// SPDX-License-Identifier: Apache-2.0
import com.swirlds.config.api.ConfigurationExtension;
import org.hiero.consensus.metrics.config.MetricsConfigurationExtension;

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
    requires transitive com.swirlds.metrics.api;
    requires transitive com.swirlds.metrics.impl;
    requires transitive org.hiero.consensus.model;
    requires transitive jdk.httpserver;
    requires transitive simpleclient;
    requires com.swirlds.logging;
    requires org.hiero.base.concurrent;
    requires org.hiero.base.utility;
    requires org.hiero.consensus.concurrent;
    requires org.apache.logging.log4j;
    requires simpleclient.httpserver;
    requires static transitive com.github.spotbugs.annotations;

    provides ConfigurationExtension with
            MetricsConfigurationExtension;
}
