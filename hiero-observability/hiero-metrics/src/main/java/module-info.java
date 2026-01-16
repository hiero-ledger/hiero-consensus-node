// SPDX-License-Identifier: Apache-2.0
module org.hiero.metrics {
    uses org.hiero.metrics.core.MetricsRegistrationProvider;
    uses org.hiero.metrics.core.MetricsExporterFactory;

    exports org.hiero.metrics;
    exports org.hiero.metrics.core;

    requires static transitive com.swirlds.config.api;
    requires static transitive com.github.spotbugs.annotations;
}
