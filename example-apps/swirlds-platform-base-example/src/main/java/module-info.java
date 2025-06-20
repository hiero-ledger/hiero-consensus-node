// SPDX-License-Identifier: Apache-2.0
import com.swirlds.config.api.ConfigurationExtension;
import com.swirlds.platform.base.example.ext.BaseConfigurationExtension;

module com.swirlds.platform.base.example {
    exports com.swirlds.platform.base.example.ext to
            com.swirlds.config.impl;

    opens com.swirlds.platform.base.example.store.domain to
            com.fasterxml.jackson.databind;

    exports com.swirlds.platform.base.example.server to
            com.swirlds.config.impl;

    requires com.swirlds.base;
    requires com.swirlds.common;
    requires com.swirlds.config.api;
    requires com.swirlds.config.extensions;
    requires com.swirlds.metrics.api;
    requires org.hiero.consensus.model;
    requires com.fasterxml.jackson.databind;
    requires com.google.common;
    requires jdk.httpserver;
    requires jdk.management;
    requires org.apache.logging.log4j;
    requires static com.github.spotbugs.annotations;
    requires static com.google.auto.service;

    provides ConfigurationExtension with
            BaseConfigurationExtension;
}
