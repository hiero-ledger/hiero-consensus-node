// SPDX-License-Identifier: Apache-2.0
import com.swirlds.config.api.ConfigurationExtension;
import com.swirlds.config.api.spi.ConfigurationBuilderFactory;
import com.swirlds.config.impl.internal.ConfigurationBuilderFactoryImpl;

module com.swirlds.config.impl {
    exports com.swirlds.config.impl.converters;
    exports com.swirlds.config.impl.validators;

    requires transitive com.swirlds.config.api;
    requires com.swirlds.base;
    requires com.swirlds.config.extensions;
    requires static transitive com.github.spotbugs.annotations;
    requires static transitive com.google.auto.service;

    uses ConfigurationExtension;

    provides ConfigurationBuilderFactory with
            ConfigurationBuilderFactoryImpl;
}
