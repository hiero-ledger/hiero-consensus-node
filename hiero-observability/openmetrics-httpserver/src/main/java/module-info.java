// SPDX-License-Identifier: Apache-2.0
module org.hiero.metrics.openmetrics.httpserver {
    exports org.hiero.metrics.openmetrics.config to
            com.swirlds.config.impl,
            com.swirlds.config.extensions;

    requires transitive com.swirlds.config.api;
    requires transitive org.hiero.metrics;
    requires jdk.httpserver;
    requires static transitive com.github.spotbugs.annotations;

    provides org.hiero.metrics.core.MetricsExporterFactory with
            org.hiero.metrics.openmetrics.OpenMetricsHttpServerFactory;
    provides com.swirlds.config.api.ConfigurationExtension with
            org.hiero.metrics.openmetrics.config.OpenMetricsHttpServerConfigurationExtension;
}
