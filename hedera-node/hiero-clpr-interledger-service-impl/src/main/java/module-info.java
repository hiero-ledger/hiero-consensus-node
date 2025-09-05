// SPDX-License-Identifier: Apache-2.0
module org.hiero.interledger.clpr.impl {
    exports org.hiero.interledger.clpr.impl;
    exports org.hiero.interledger.clpr.impl.handlers;

    requires transitive com.hedera.node.app.spi;
    requires transitive com.hedera.node.hapi;
    requires transitive com.swirlds.state.api;
    requires transitive org.hiero.interledger.clpr;
    requires transitive dagger;
    requires transitive javax.inject;
    requires com.hedera.pbj.runtime;
    requires org.apache.logging.log4j;
    requires static transitive com.github.spotbugs.annotations;
}
