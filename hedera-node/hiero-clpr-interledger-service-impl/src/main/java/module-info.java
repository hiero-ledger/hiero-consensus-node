// SPDX-License-Identifier: Apache-2.0
module org.hiero.interledger.clpr.impl {
    requires transitive org.hiero.interledger.clpr;

    exports org.hiero.interledger.clpr.impl;

    requires transitive com.hedera.node.app.spi;
    requires transitive com.hedera.pbj.runtime;
    requires transitive com.swirlds.state.api;
    requires transitive com.hedera.node.hapi;
    requires static transitive com.github.spotbugs.annotations;
    requires com.hedera.node.app;
}
