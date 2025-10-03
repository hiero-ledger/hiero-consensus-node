// SPDX-License-Identifier: Apache-2.0
/**
 * Module that provides the implementation of the Hedera Token Service.
 */
module com.hedera.node.app.service.token.impl {
    requires transitive com.hedera.node.app.hapi.fees;
    requires transitive com.hedera.node.app.hapi.utils;
    requires transitive com.hedera.node.app.service.token;
    requires transitive com.hedera.node.app.spi;
    requires transitive com.hedera.node.config;
    requires transitive com.hedera.node.hapi;
    requires transitive com.hedera.pbj.runtime;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.metrics.api;
    requires transitive com.swirlds.state.api;
    requires transitive com.esaulpaugh.headlong;
    requires transitive dagger;
    requires transitive javax.inject;
    requires transitive org.apache.commons.lang3;
    requires com.swirlds.base;
    requires com.swirlds.common;
    requires com.github.spotbugs.annotations;
    requires com.google.common;
    requires org.apache.logging.log4j;
    requires org.bouncycastle.provider;
    requires org.slf4j;
    requires tuweni.bytes;

    exports com.hedera.node.app.service.token.impl.handlers;
    exports com.hedera.node.app.service.token.impl;
    exports com.hedera.node.app.service.token.impl.api;
    exports com.hedera.node.app.service.token.impl.validators;
    exports com.hedera.node.app.service.token.impl.util;
    exports com.hedera.node.app.service.token.impl.handlers.staking;
    exports com.hedera.node.app.service.token.impl.handlers.transfer;
    exports com.hedera.node.app.service.token.impl.schemas;
    exports com.hedera.node.app.service.token.impl.comparator;
    exports com.hedera.node.app.service.token.impl.handlers.transfer.hooks;
    exports com.hedera.node.app.service.token.impl.handlers.transfer.customfees;
}
