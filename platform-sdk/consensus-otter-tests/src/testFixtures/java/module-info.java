// SPDX-License-Identifier: Apache-2.0
module org.hiero.otter.fixtures {
    requires transitive com.hedera.node.hapi;
    requires transitive com.hedera.pbj.runtime;
    requires transitive com.swirlds.base.test.fixtures;
    requires transitive com.swirlds.base;
    requires transitive com.swirlds.common;
    requires transitive com.swirlds.component.framework;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.logging;
    requires transitive com.swirlds.metrics.api;
    requires transitive com.swirlds.platform.core;
    requires transitive com.swirlds.state.api;
    requires transitive com.swirlds.state.impl;
    requires transitive org.hiero.consensus.gossip.impl;
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
    requires com.hedera.node.app.hapi.utils;
    requires com.swirlds.common.test.fixtures;
    requires com.swirlds.config.extensions.test.fixtures;
    requires com.swirlds.config.extensions;
    requires com.swirlds.merkledb;
    requires com.swirlds.virtualmap;
    requires org.hiero.base.utility;
    requires org.hiero.consensus.concurrent;
    requires org.hiero.consensus.hashgraph.impl.test.fixtures;
    requires org.hiero.consensus.hashgraph;
    requires org.hiero.consensus.metrics;
    requires org.hiero.consensus.pces.impl;
    requires org.hiero.consensus.pces;
    requires org.hiero.consensus.platformstate;
    requires org.hiero.consensus.roster;
    requires org.hiero.consensus.state;
    requires com.fasterxml.jackson.annotation;
    requires com.fasterxml.jackson.core;
    requires com.fasterxml.jackson.databind;
    requires com.fasterxml.jackson.dataformat.yaml;
    requires com.fasterxml.jackson.datatype.jsr310;
    requires com.github.dockerjava.api;
    requires io.grpc.protobuf;
    requires java.net.http;
    requires org.antlr.antlr4.runtime;
    requires org.apache.commons.lang3;
    requires org.junit.jupiter.params;
    requires org.junit.platform.commons;
    requires static com.github.spotbugs.annotations;

    exports org.hiero.otter.fixtures;
    exports org.hiero.otter.fixtures.assertions;
    exports org.hiero.otter.fixtures.chaosbot;
    exports org.hiero.otter.fixtures.exceptions;
    exports org.hiero.otter.fixtures.junit;
    exports org.hiero.otter.fixtures.logging;
    exports org.hiero.otter.fixtures.network;
    exports org.hiero.otter.fixtures.network.transactions;
    exports org.hiero.otter.fixtures.result;
    exports org.hiero.otter.fixtures.specs;
    exports org.hiero.otter.fixtures.util;
    exports org.hiero.otter.fixtures.app to
            org.hiero.otter.test.performance,
            com.swirlds.config.extensions,
            com.swirlds.config.impl,
            org.hiero.otter.test,
            org.hiero.consensus.otter.docker.app;
    exports org.hiero.otter.fixtures.app.services.consistency to
            com.swirlds.config.extensions,
            com.swirlds.config.impl;
    exports org.hiero.otter.fixtures.container to
            com.swirlds.config.impl,
            org.hiero.otter.fixtures.test;
    exports org.hiero.otter.fixtures.container.proto to
            org.hiero.consensus.otter.docker.app;
    exports org.hiero.otter.fixtures.container.utils to
            org.hiero.consensus.otter.docker.app;
    exports org.hiero.otter.fixtures.internal to
            com.swirlds.config.impl,
            org.hiero.consensus.otter.docker.app,
            org.hiero.otter.fixtures.test;
    exports org.hiero.otter.fixtures.internal.helpers to
            org.hiero.consensus.otter.docker.app;
    exports org.hiero.otter.fixtures.logging.internal to
            org.hiero.consensus.otter.docker.app;
    exports org.hiero.otter.fixtures.turtle to
            org.apache.logging.log4j.core,
            org.hiero.otter.fixtures.test;

    opens org.hiero.otter.fixtures.container.network to
            com.fasterxml.jackson.databind;
}
