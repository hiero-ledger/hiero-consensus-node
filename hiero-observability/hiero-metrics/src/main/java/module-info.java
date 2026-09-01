// SPDX-License-Identifier: Apache-2.0
module org.hiero.metrics {
    exports org.hiero.metrics.core;
    exports org.hiero.metrics.export;
    exports org.hiero.metrics;

    requires static transitive com.swirlds.config.api;
    requires static transitive com.github.spotbugs.annotations;

    uses org.hiero.metrics.core.MetricsExporterFactory;
    uses org.hiero.metrics.core.MetricsRegistrationProvider;
}
