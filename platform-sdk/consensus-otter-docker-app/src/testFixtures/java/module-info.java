// SPDX-License-Identifier: Apache-2.0
module org.hiero.consensus.otter.docker.app {
    requires transitive com.hedera.node.hapi;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.platform.core;
    requires transitive org.hiero.consensus.model;
    requires transitive org.hiero.otter.fixtures;
    requires transitive com.google.protobuf;
    requires transitive io.grpc.stub;
    requires transitive org.apache.logging.log4j.core;
    requires com.hedera.pbj.runtime;
    requires com.swirlds.base;
    requires com.swirlds.common;
    requires com.swirlds.component.framework;
    requires com.swirlds.metrics.api;
    requires com.swirlds.platform.core.test.fixtures;
    requires com.swirlds.state.api;
    requires com.swirlds.virtualmap;
    requires org.hiero.consensus.utility;
    requires io.grpc;
    requires org.apache.logging.log4j;
    requires static transitive com.github.spotbugs.annotations;
}
