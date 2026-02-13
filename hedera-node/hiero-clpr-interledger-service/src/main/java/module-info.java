// SPDX-License-Identifier: Apache-2.0
module org.hiero.interledger.clpr {
    exports org.hiero.interledger.clpr;
    exports org.hiero.interledger.clpr.client;

    uses org.hiero.interledger.clpr.ClprService;

    requires transitive com.hedera.node.app.spi;
    requires transitive com.hedera.node.hapi;
    requires transitive com.hedera.pbj.runtime;
    requires com.hedera.node.app.hapi.utils;
    requires com.swirlds.state.api;
    requires static transitive com.github.spotbugs.annotations;
}
