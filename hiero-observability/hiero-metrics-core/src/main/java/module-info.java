// SPDX-License-Identifier: Apache-2.0
module org.hiero.metrics.core {
    uses org.hiero.metrics.api.core.MetricsRegistrationProvider;
    uses org.hiero.metrics.api.export.MetricsExporterFactory;

    exports org.hiero.metrics.api;
    exports org.hiero.metrics.api.core;
    exports org.hiero.metrics.api.measurement;
    exports org.hiero.metrics.api.export;
    exports org.hiero.metrics.api.export.snapshot;

    requires static transitive com.swirlds.config.api;
    requires static transitive com.github.spotbugs.annotations;
    requires static transitive com.google.auto.service;
}
