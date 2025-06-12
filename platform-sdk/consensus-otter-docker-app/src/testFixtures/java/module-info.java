// SPDX-License-Identifier: Apache-2.0
module org.hiero.consensus.otter.docker.app {
    requires transitive com.hedera.node.hapi;
    requires transitive com.hedera.pbj.runtime;
    requires transitive com.swirlds.platform.core;
    requires transitive org.hiero.consensus.model;
    requires com.swirlds.component.framework;
    requires com.swirlds.logging;
    requires org.apache.logging.log4j;
    requires static transitive com.github.spotbugs.annotations;
    requires com.swirlds.merkledb;
    requires com.swirlds.config.extensions;
    requires com.swirlds.platform.core.test.fixtures;
    requires org.hiero.otter.fixtures;
    requires io.grpc.netty;
    requires io.netty.handler;
    requires io.netty.transport.classes.epoll;
    requires io.netty.transport;
    requires io.netty.codec.http;
    requires io.netty.buffer;
    requires io.netty.common;
}
