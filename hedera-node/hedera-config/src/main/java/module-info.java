// SPDX-License-Identifier: Apache-2.0
module com.hedera.node.config {
    exports com.hedera.node.config;
    exports com.hedera.node.config.converter;
    exports com.hedera.node.config.data;
    exports com.hedera.node.config.sources;
    exports com.hedera.node.config.types;
    exports com.hedera.node.config.validation;

    requires transitive com.hedera.node.app.hapi.utils;
    requires transitive com.hedera.node.hapi;
    requires transitive com.hedera.pbj.runtime;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.config.extensions;
    requires org.hiero.base.utility;
    requires org.apache.commons.lang3;
    requires org.apache.logging.log4j;
    requires static transitive com.github.spotbugs.annotations;
}
