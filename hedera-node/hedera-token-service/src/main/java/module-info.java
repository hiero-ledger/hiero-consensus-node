// SPDX-License-Identifier: Apache-2.0
/**
 * Provides the classes necessary to manage Hedera Token Service.
 */
module com.hedera.node.app.service.token {
    exports com.hedera.node.app.service.token;
    exports com.hedera.node.app.service.token.api;
    exports com.hedera.node.app.service.token.records;

    uses com.hedera.node.app.service.token.TokenService;

    requires transitive com.hedera.node.app.service.entityid;
    requires transitive com.hedera.node.app.spi;
    requires transitive com.hedera.node.hapi;
    requires transitive com.hedera.pbj.runtime;
    requires transitive com.swirlds.config.api;
    requires transitive com.esaulpaugh.headlong;
    requires transitive org.apache.logging.log4j;
    requires com.hedera.node.app.hapi.utils;
    requires com.swirlds.common;
    requires com.swirlds.state.api;
    requires org.hiero.base.utility;
    requires com.github.spotbugs.annotations;
}
