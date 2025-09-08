// SPDX-License-Identifier: Apache-2.0
/**
 * Module that provides the implementation of the Hedera Token Service.
 */
module com.hedera.node.app.service.token.impl {
    requires transitive com.hedera.node.app.hapi.fees;
    requires transitive com.hedera.node.app.service.token;
    requires transitive com.hedera.node.config;
    requires com.hedera.node.app.service.contract; // javax.annotation.processing.Generated
    requires org.bouncycastle.provider;

    exports com.hedera.node.app.service.token.impl.handlers;
    exports com.hedera.node.app.service.token.impl;
    exports com.hedera.node.app.service.token.impl.api;
    exports com.hedera.node.app.service.token.impl.validators;
    exports com.hedera.node.app.service.token.impl.util;
    exports com.hedera.node.app.service.token.impl.handlers.staking;
    exports com.hedera.node.app.service.token.impl.handlers.transfer;
    exports com.hedera.node.app.service.token.impl.schemas;
    exports com.hedera.node.app.service.token.impl.comparator;
}
