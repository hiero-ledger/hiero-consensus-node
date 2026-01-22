// SPDX-License-Identifier: Apache-2.0
module com.hedera.node.app.service.schedule.impl {
    requires transitive com.hedera.node.app.hapi.fees;
    requires transitive com.hedera.node.app.service.entityid;
    requires transitive com.hedera.node.app.service.schedule;
    requires transitive com.hedera.node.app.service.token;
    requires transitive com.hedera.node.app.spi;
    requires transitive com.hedera.node.config;
    requires transitive com.hedera.node.hapi;
    requires transitive com.hedera.pbj.runtime;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.state.api;
    requires transitive dagger;
    requires transitive static java.compiler; // javax.annotation.processing.Generated
    requires transitive javax.inject;
    requires com.hedera.node.app.hapi.utils;
    requires com.google.common;
    requires org.apache.logging.log4j;
    requires static transitive com.github.spotbugs.annotations;

    exports com.hedera.node.app.service.schedule.impl;
    exports com.hedera.node.app.service.schedule.impl.handlers;
    exports com.hedera.node.app.service.schedule.impl.schemas;
}
