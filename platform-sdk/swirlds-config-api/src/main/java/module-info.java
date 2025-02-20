// SPDX-License-Identifier: Apache-2.0
import com.swirlds.config.api.spi.ConfigurationBuilderFactory;

module com.swirlds.config.api {
    exports com.swirlds.config.api;
    exports com.swirlds.config.api.spi;
    exports com.swirlds.config.api.converter;
    exports com.swirlds.config.api.source;
    exports com.swirlds.config.api.validation;
    exports com.swirlds.config.api.validation.annotation;

    uses ConfigurationBuilderFactory;

    requires static transitive com.github.spotbugs.annotations;
}
