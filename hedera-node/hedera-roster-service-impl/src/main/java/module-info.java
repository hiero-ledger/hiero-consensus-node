// SPDX-License-Identifier: Apache-2.0
module com.hedera.node.app.service.roster.impl {
    requires transitive com.hedera.node.app.service.roster;
    requires transitive com.hedera.node.hapi;
    requires transitive com.hedera.pbj.runtime;
    requires transitive com.swirlds.platform.core;
    requires transitive com.swirlds.state.api;
    requires transitive org.hiero.consensus.utility;
    requires com.hedera.node.app.spi;
    requires com.hedera.node.config;
    requires com.swirlds.config.api;
    requires org.apache.logging.log4j;
    requires static transitive com.github.spotbugs.annotations;

    exports com.hedera.node.app.service.roster.impl;
    exports com.hedera.node.app.service.roster.impl.schemas;
}
