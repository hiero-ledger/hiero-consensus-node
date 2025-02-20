// SPDX-License-Identifier: Apache-2.0
module com.swirlds.config.extensions {
    exports com.swirlds.config.extensions;
    exports com.swirlds.config.extensions.export;
    exports com.swirlds.config.extensions.reflection;
    exports com.swirlds.config.extensions.sources;
    exports com.swirlds.config.extensions.validators;

    requires transitive com.swirlds.config.api;
    requires com.swirlds.base;
    requires com.fasterxml.jackson.core;
    requires com.fasterxml.jackson.databind;
    requires com.fasterxml.jackson.dataformat.yaml;
    requires org.apache.logging.log4j;
    requires static transitive com.github.spotbugs.annotations;
}
