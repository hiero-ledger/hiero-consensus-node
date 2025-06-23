// SPDX-License-Identifier: Apache-2.0
module org.hiero.consensus.otter.docker.app {
    requires transitive com.hedera.node.hapi;
    requires transitive com.swirlds.common;
    requires transitive com.swirlds.platform.core;
    requires transitive org.hiero.consensus.model;
    requires com.hedera.pbj.runtime;
    requires com.swirlds.base;
    requires com.swirlds.component.framework;
    requires com.swirlds.config.api;
    requires com.swirlds.config.extensions.test.fixtures;
    requires com.swirlds.metrics.api;
    requires com.swirlds.state.api;
    requires org.hiero.consensus.utility;
    requires org.hiero.otter.fixtures;
    requires io.grpc.stub;
    requires io.grpc;
    requires org.apache.logging.log4j;
    requires static transitive com.github.spotbugs.annotations;
}
