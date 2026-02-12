// SPDX-License-Identifier: Apache-2.0
module org.hiero.interledger.clpr.impl {
    exports org.hiero.interledger.clpr.impl;
    exports org.hiero.interledger.clpr.impl.handlers;
    exports org.hiero.interledger.clpr.impl.client;

    requires transitive com.hedera.node.app.spi;
    requires transitive com.hedera.node.config;
    requires transitive com.hedera.node.hapi;
    requires transitive com.swirlds.state.api;
    requires transitive org.hiero.interledger.clpr;
    requires transitive dagger;
    requires transitive io.grpc.stub;
    requires transitive io.grpc;
    requires transitive io.helidon.grpc.core;
    requires transitive io.helidon.webclient.grpc;
    requires transitive javax.inject;
    requires com.hedera.node.app.hapi.utils;
    requires com.hedera.pbj.grpc.client.helidon;
    requires com.hedera.pbj.runtime;
    requires com.swirlds.metrics.api;
    requires com.swirlds.state.impl;
    requires org.hiero.consensus.concurrent;
    requires org.hiero.consensus.roster;
    requires org.hiero.consensus.utility;
    requires io.helidon.common.tls;
    requires org.apache.logging.log4j;
    requires static transitive com.github.spotbugs.annotations;
}
