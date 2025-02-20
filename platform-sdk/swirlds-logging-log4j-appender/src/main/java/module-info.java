// SPDX-License-Identifier: Apache-2.0
import com.swirlds.logging.api.extensions.provider.LogProviderFactory;
import com.swirlds.logging.log4j.factory.BaseLoggingProvider;
import com.swirlds.logging.log4j.factory.Log4JProviderFactory;
import org.apache.logging.log4j.spi.Provider;

module com.swirlds.logging.log4j.appender {
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.logging;
    requires transitive org.apache.logging.log4j;
    requires static transitive com.github.spotbugs.annotations;
    requires static transitive com.google.auto.service;

    provides Provider with
            BaseLoggingProvider;
    provides LogProviderFactory with
            Log4JProviderFactory;

    opens com.swirlds.logging.log4j.factory to
            org.apache.logging.log4j,
            com.swirlds.logging;
}
