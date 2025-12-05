// SPDX-License-Identifier: Apache-2.0
module org.hiero.metrics.core {
    uses org.hiero.metrics.api.core.MetricsRegistrationProvider;
    uses org.hiero.metrics.api.export.MetricsExporterFactory;

    exports org.hiero.metrics.api;
    exports org.hiero.metrics.api.core;
    exports org.hiero.metrics.api.utils;
    exports org.hiero.metrics.api.datapoint;
    exports org.hiero.metrics.api.export;
    exports org.hiero.metrics.api.export.snapshot;
    exports org.hiero.metrics.api.export.extension;
    exports org.hiero.metrics.api.export.extension.writer;
    exports org.hiero.metrics.api.stat;
    exports org.hiero.metrics.api.stat.container;
    exports org.hiero.metrics.internal.export.config to
            com.swirlds.config.impl,
            com.swirlds.config.extensions;

    requires transitive com.swirlds.base;
    requires java.management;
    requires jdk.management;
    requires org.apache.logging.log4j;
    requires static transitive com.swirlds.config.api;
    requires static transitive com.github.spotbugs.annotations;
    requires static transitive com.google.auto.service;

    provides org.hiero.metrics.api.core.MetricsRegistrationProvider with
            org.hiero.metrics.internal.JvmMetricsRegistration;
    provides com.swirlds.config.api.ConfigurationExtension with
            org.hiero.metrics.internal.export.config.MetricsExportManagerConfigurationExtension;
}
