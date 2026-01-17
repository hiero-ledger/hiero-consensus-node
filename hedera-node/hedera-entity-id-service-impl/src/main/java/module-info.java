// SPDX-License-Identifier: Apache-2.0
import com.hedera.node.app.service.entityid.EntityIdService;
import com.hedera.node.app.service.entityid.impl.EntityIdServiceImpl;

/**
 * Module that provides the implementation of the Hedera Entity ID Service.
 */
module com.hedera.node.app.service.entityid.impl {
    requires transitive com.hedera.node.app.hapi.utils;
    requires transitive com.hedera.node.app.service.entityid;
    requires transitive com.hedera.node.hapi;
    requires transitive com.hedera.pbj.runtime;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.state.api;
    requires transitive javax.inject;
    requires com.hedera.node.config;
    requires org.hiero.base.utility;
    requires com.google.common;
    requires org.apache.logging.log4j;
    requires static transitive com.github.spotbugs.annotations;

    provides EntityIdService with
            EntityIdServiceImpl;

    exports com.hedera.node.app.service.entityid.impl.schemas;
    exports com.hedera.node.app.service.entityid.impl;
}
