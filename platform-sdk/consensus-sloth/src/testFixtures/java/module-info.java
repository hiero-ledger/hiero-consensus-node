// SPDX-License-Identifier: Apache-2.0
module org.hiero.sloth.fixtures {
    requires transitive com.hedera.node.hapi;
    requires transitive com.hedera.pbj.runtime;
    requires transitive com.swirlds.base;
    requires transitive com.swirlds.common;
    requires transitive com.swirlds.component.framework;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.logging;
    requires transitive com.swirlds.metrics.api;
    requires transitive com.swirlds.platform.core;
    requires transitive com.swirlds.state.api;
    requires transitive com.swirlds.state.impl;
    requires transitive org.hiero.consensus.gossip;
    requires transitive org.hiero.consensus.model;
    requires transitive org.hiero.consensus.utility.test.fixtures;
    requires transitive org.hiero.consensus.utility;
    requires transitive com.google.common;
    requires transitive com.google.protobuf;
    requires transitive io.grpc.stub;
    requires transitive io.grpc;
    requires transitive org.apache.logging.log4j.core;
    requires transitive org.apache.logging.log4j;
    requires transitive org.assertj.core;
    requires transitive org.junit.jupiter.api;
    requires transitive org.testcontainers;
    requires com.swirlds.config.extensions.test.fixtures;
    requires com.swirlds.config.extensions;
    requires org.hiero.consensus.concurrent;
    requires org.hiero.consensus.hashgraph;
    requires org.hiero.consensus.metrics;
    requires org.hiero.consensus.platformstate;
    requires org.hiero.consensus.roster;
    requires org.hiero.consensus.state;
    requires com.fasterxml.jackson.core;
    requires com.fasterxml.jackson.databind;
    requires com.fasterxml.jackson.dataformat.yaml;
    requires com.github.dockerjava.api;
    requires io.grpc.protobuf;
    requires java.net.http;
    requires org.apache.commons.lang3;
    requires org.junit.jupiter.params;
    requires org.junit.platform.commons;
    requires static com.github.spotbugs.annotations;

    exports org.hiero.sloth.fixtures;
    exports org.hiero.sloth.fixtures.exceptions;
    exports org.hiero.sloth.fixtures.junit;
    exports org.hiero.sloth.fixtures.logging;
    exports org.hiero.sloth.fixtures.network;
    exports org.hiero.sloth.fixtures.network.transactions;
    exports org.hiero.sloth.fixtures.result;
    exports org.hiero.sloth.fixtures.specs;
    exports org.hiero.sloth.fixtures.util;
    exports org.hiero.sloth.fixtures.app to
            org.hiero.sloth.test.performance,
            com.swirlds.config.extensions,
            com.swirlds.config.impl;
    exports org.hiero.sloth.fixtures.container to
            com.swirlds.config.impl;
    exports org.hiero.sloth.fixtures.container.proto to
            org.hiero.sloth.test.performance;
    exports org.hiero.sloth.fixtures.container.utils to
            org.hiero.sloth.test.performance;
    exports org.hiero.sloth.fixtures.internal to
            com.swirlds.config.impl;
    exports org.hiero.sloth.fixtures.internal.helpers to
            org.hiero.sloth.test.performance;
    exports org.hiero.sloth.fixtures.logging.internal to
            org.hiero.sloth.test.performance;

    opens org.hiero.sloth.fixtures.container.proto to
            com.google.protobuf;
    opens org.hiero.sloth.fixtures.network.transactions to
            com.google.protobuf;
}
