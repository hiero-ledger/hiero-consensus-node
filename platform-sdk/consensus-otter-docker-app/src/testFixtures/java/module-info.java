// SPDX-License-Identifier: Apache-2.0
module org.hiero.consensus.otter.docker.app {
    requires transitive com.hedera.node.hapi;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.platform.core;
    requires transitive org.hiero.consensus.model;
    requires transitive org.hiero.otter.fixtures;
    requires transitive org.apache.logging.log4j.core;
    requires com.swirlds.base;
    requires com.swirlds.common;
    requires com.swirlds.component.framework;
    requires com.swirlds.logging;
    requires com.swirlds.metrics.api;
    requires com.swirlds.platform.core.test.fixtures;
    requires com.swirlds.state.api;
    requires org.hiero.consensus.utility;
    requires com.swirlds.state.impl;
    requires com.swirlds.virtualmap;
    requires org.hiero.consensus.concurrent;
    requires org.hiero.consensus.metrics;
    requires org.hiero.consensus.roster;
    requires org.apache.logging.log4j;
    requires com.hedera.pbj.runtime;
    requires com.hedera.pbj.grpc.helidon;
    requires io.helidon.webserver;
    requires static transitive com.github.spotbugs.annotations;
}
