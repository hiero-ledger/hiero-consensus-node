// SPDX-License-Identifier: Apache-2.0
module org.hiero.interledger.clpr.impl {
    exports org.hiero.interledger.clpr.impl;
    exports org.hiero.interledger.clpr.impl.handlers;
    exports org.hiero.interledger.clpr.impl.client;

    requires transitive com.hedera.node.app.spi;
    requires transitive com.hedera.node.config;
    requires transitive com.hedera.node.hapi;
    requires transitive com.swirlds.metrics.api;
    requires transitive com.swirlds.state.api;
    requires transitive org.hiero.interledger.clpr;
    requires transitive dagger;
    requires transitive javax.inject;
    requires com.hedera.node.app.hapi.utils;
    requires com.hedera.pbj.grpc.client.helidon;
    requires com.hedera.pbj.runtime;
    requires com.swirlds.state.impl;
    requires com.swirlds.virtualmap;
    requires org.hiero.base.crypto;
    requires org.hiero.consensus.concurrent;
    requires org.hiero.consensus.platformstate;
    requires org.hiero.consensus.roster;
    requires io.helidon.common.tls;
    requires io.helidon.webclient.api;
    requires net.i2p.crypto.eddsa;
    requires org.apache.logging.log4j;
    requires static transitive com.github.spotbugs.annotations;
}
