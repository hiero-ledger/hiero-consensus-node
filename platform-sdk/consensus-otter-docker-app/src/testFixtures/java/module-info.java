// SPDX-License-Identifier: Apache-2.0
module org.hiero.consensus.otter.docker.app {
    requires transitive com.hedera.node.hapi;
    requires transitive com.swirlds.common;
    requires transitive com.swirlds.platform.core;
    requires transitive org.hiero.base.utility;
    requires transitive org.hiero.consensus.model;
    requires transitive io.netty.codec.http;
    requires transitive io.netty.transport;
    requires com.hedera.pbj.runtime;
    requires com.swirlds.base;
    requires com.swirlds.config.api;
    requires com.swirlds.config.extensions;
    requires com.swirlds.metrics.api;
    requires com.swirlds.platform.core.test.fixtures;
    requires com.swirlds.state.api;
    requires org.hiero.consensus.utility;
    requires org.hiero.otter.fixtures;
    requires com.fasterxml.jackson.databind;
    requires io.netty.buffer;
    requires io.netty.common;
    requires org.apache.logging.log4j;
    requires static transitive com.github.spotbugs.annotations;
}
