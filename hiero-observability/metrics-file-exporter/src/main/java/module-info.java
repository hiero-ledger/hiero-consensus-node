// SPDX-License-Identifier: Apache-2.0
module org.hiero.metrics.export.file {
    exports org.hiero.metrics.export.file.config to
            com.swirlds.config.impl,
            com.swirlds.config.extensions;

    requires transitive com.swirlds.config.api;
    requires transitive org.hiero.metrics;
    requires static transitive com.github.spotbugs.annotations;

    provides org.hiero.metrics.core.MetricsExporterFactory with
            org.hiero.metrics.export.file.MetricsFileExporterFactory;
    provides com.swirlds.config.api.ConfigurationExtension with
            org.hiero.metrics.export.file.config.MetricsFileExportConfigurationExtension;
}
