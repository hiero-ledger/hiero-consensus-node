// SPDX-License-Identifier: Apache-2.0
/**
 * Module that provides the implementation of the Entity ID service
 */
module com.hedera.node.app.service.entity {
    exports com.hedera.node.app.service.entity.schemas;
    exports com.hedera.node.app.service.entity;

    requires transitive com.hedera.node.config;
    requires transitive com.hedera.node.hapi;
    requires transitive com.swirlds.state.api;
}
