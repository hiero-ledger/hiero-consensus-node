// SPDX-License-Identifier: Apache-2.0
module org.hiero.consensus.otter.docker.app {
    requires transitive com.hedera.node.hapi;
    requires transitive com.hedera.pbj.runtime;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.platform.core;
    requires transitive org.hiero.consensus.metrics;
    requires transitive org.hiero.consensus.model;
    requires transitive org.hiero.otter.fixtures;
    requires transitive org.apache.logging.log4j.core;
    requires com.hedera.pbj.grpc.helidon.config;
    requires com.hedera.pbj.grpc.helidon;
    requires com.swirlds.base;
    requires com.swirlds.common;
    requires com.swirlds.logging;
    requires com.swirlds.metrics.api;
    requires com.swirlds.platform.core.test.fixtures;
    requires com.swirlds.state.api;
    requires com.swirlds.state.impl;
    requires com.swirlds.virtualmap;
    requires org.hiero.base.concurrent;
    requires org.hiero.base.utility;
    requires org.hiero.consensus.hashgraph;
    requires org.hiero.consensus.platformstate;
    requires org.hiero.consensus.roster;
    requires org.hiero.consensus.state;
    requires org.hiero.consensus.utility.test.fixtures;
    requires org.hiero.consensus.utility;
    requires org.hiero.consensus.wiring.framework;
    requires io.helidon.common;
    requires io.helidon.webserver;
    requires org.apache.logging.log4j;
    requires static transitive com.github.spotbugs.annotations;
}
