// SPDX-License-Identifier: Apache-2.0
module com.hedera.node.app.service.util.impl {
    requires transitive com.hedera.node.app.service.util;
    requires transitive com.hedera.node.app.spi;
    requires transitive com.hedera.node.hapi;
    requires transitive com.hedera.pbj.runtime;
    requires transitive com.swirlds.config.api;
    requires transitive dagger;
    requires transitive java.compiler; // javax.annotation.processing.Generated
    requires transitive javax.inject;
    requires com.hedera.node.app.service.token;
    requires com.hedera.node.config;
    requires com.github.benmanes.caffeine;
    requires org.apache.logging.log4j;
    requires static transitive com.github.spotbugs.annotations;

    exports com.hedera.node.app.service.util.impl.handlers;
    exports com.hedera.node.app.service.util.impl.records;
    exports com.hedera.node.app.service.util.impl.cache;
    exports com.hedera.node.app.service.util.impl;
}
