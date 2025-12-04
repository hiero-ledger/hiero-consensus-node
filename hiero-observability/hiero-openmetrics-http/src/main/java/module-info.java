// SPDX-License-Identifier: Apache-2.0
module org.hiero.metrics.openmetrics.http {
    exports org.hiero.metrics.openmetrics.config to
            com.swirlds.config.impl,
            com.swirlds.config.extensions;

    requires transitive com.swirlds.config.api;
    requires transitive org.hiero.metrics.core;
    requires jdk.httpserver;
    requires org.apache.logging.log4j;
    requires static transitive com.github.spotbugs.annotations;
    requires static transitive com.google.auto.service;

    provides org.hiero.metrics.api.export.MetricsExporterFactory with
            org.hiero.metrics.openmetrics.OpenMetricsHttpEndpointFactory;
    provides com.swirlds.config.api.ConfigurationExtension with
            org.hiero.metrics.openmetrics.config.OpenMetricsHttpEndpointConfigurationExtension;
}
