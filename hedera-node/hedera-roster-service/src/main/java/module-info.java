// SPDX-License-Identifier: Apache-2.0
/**
 * Provides the classes necessary to manage Hedera Roster Service.
 */
module com.hedera.node.app.service.roster {
    exports com.hedera.node.app.service.roster;

    requires transitive com.hedera.node.app.spi;
    requires transitive com.hedera.node.hapi;
    requires transitive com.swirlds.state.api;
    requires transitive org.hiero.consensus.utility;
    requires transitive org.apache.logging.log4j;
    requires com.hedera.node.config;
    requires com.swirlds.config.api;
    requires static transitive com.github.spotbugs.annotations;

    uses com.hedera.node.app.service.roster.RosterService;
}
