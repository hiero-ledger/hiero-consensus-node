// SPDX-License-Identifier: Apache-2.0
import com.swirlds.logging.api.extensions.handler.LogHandlerFactory;
import com.swirlds.logging.api.extensions.provider.LogProviderFactory;
import com.swirlds.logging.console.ConsoleHandlerFactory;
import com.swirlds.logging.file.FileHandlerFactory;

module com.swirlds.logging {
    exports com.swirlds.logging.legacy;
    exports com.swirlds.logging.legacy.json;
    exports com.swirlds.logging.legacy.payload;
    exports com.swirlds.logging.api;
    exports com.swirlds.logging.api.extensions.provider;
    exports com.swirlds.logging.api.extensions.handler;
    exports com.swirlds.logging.api.extensions.emergency;
    exports com.swirlds.logging.api.extensions.event;
    exports com.swirlds.logging.api.internal.level to
            com.swirlds.logging.test.fixtures;
    exports com.swirlds.logging.api.internal.emergency to
            com.swirlds.logging.test.fixtures;
    exports com.swirlds.logging.api.internal.format;
    exports com.swirlds.logging.api.internal;
    exports com.swirlds.logging.api.internal.event;
    exports com.swirlds.logging.api.internal.configuration;

    requires transitive com.swirlds.config.api;
    requires transitive com.fasterxml.jackson.annotation;
    requires transitive com.fasterxml.jackson.databind;
    requires transitive org.apache.logging.log4j;
    requires com.swirlds.base;
    requires com.swirlds.config.extensions;
    requires com.fasterxml.jackson.core;
    requires com.fasterxml.jackson.datatype.jsr310;
    requires static transitive com.github.spotbugs.annotations;
    requires static transitive com.google.auto.service;

    uses LogHandlerFactory;
    uses LogProviderFactory;

    provides LogHandlerFactory with
            ConsoleHandlerFactory,
            FileHandlerFactory;
}
