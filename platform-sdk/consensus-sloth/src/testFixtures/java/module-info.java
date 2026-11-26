// SPDX-License-Identifier: Apache-2.0
module org.hiero.sloth.fixtures {
    exports org.hiero.sloth.fixtures.exceptions;
    exports org.hiero.sloth.fixtures.junit;
    exports org.hiero.sloth.fixtures.logging;
    exports org.hiero.sloth.fixtures.network.transactions;
    exports org.hiero.sloth.fixtures.network;
    exports org.hiero.sloth.fixtures.result;
    exports org.hiero.sloth.fixtures.specs;
    exports org.hiero.sloth.fixtures.util;
    exports org.hiero.sloth.fixtures;
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
    exports org.hiero.sloth.fixtures.remote to
            com.swirlds.config.impl;

    requires transitive com.hedera.node.hapi;
    requires transitive com.hedera.pbj.runtime;
    requires transitive com.swirlds.base;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.logging;
    requires transitive com.swirlds.metrics.api;
    requires transitive com.swirlds.platform.core;
    requires transitive com.swirlds.state.api;
    requires transitive com.swirlds.state.impl;
    requires transitive org.hiero.consensus.adapter;
    requires transitive org.hiero.consensus.gossip;
    requires transitive org.hiero.consensus.main.model;
    requires transitive org.hiero.consensus.model;
    requires transitive org.hiero.consensus.utility.test.fixtures;
    requires transitive org.hiero.consensus.wiring.framework;
    requires transitive org.apache.logging.log4j.core;
    requires transitive org.apache.logging.log4j;
    requires transitive org.assertj.core;
    requires transitive org.junit.jupiter.api;
    requires transitive org.testcontainers;
    requires com.hedera.pbj.grpc.client.helidon;
    requires com.hedera.pbj.grpc.helidon.config;
    requires com.hedera.pbj.grpc.helidon;
    requires com.swirlds.common;
    requires com.swirlds.config.extensions.test.fixtures;
    requires com.swirlds.config.extensions;
    requires com.swirlds.platform.core.test.fixtures;
    requires org.hiero.base.concurrent;
    requires org.hiero.base.utility;
    requires org.hiero.consensus.event.stream;
    requires org.hiero.consensus.fakes;
    requires org.hiero.consensus.hashgraph;
    requires org.hiero.consensus.metrics;
    requires org.hiero.consensus.platformstate;
    requires org.hiero.consensus.roster;
    requires org.hiero.consensus.state;
    requires org.hiero.consensus.utility;
    requires com.fasterxml.jackson.core;
    requires com.fasterxml.jackson.databind;
    requires com.fasterxml.jackson.dataformat.yaml;
    requires com.github.dockerjava.api;
    requires io.helidon.common.tls;
    requires io.helidon.common;
    requires io.helidon.webclient.api;
    requires io.helidon.webserver;
    requires java.net.http;
    requires org.antlr.antlr4.runtime;
    requires org.apache.commons.lang3;
    requires org.junit.jupiter.params;
    requires org.junit.platform.commons;
    requires static com.github.spotbugs.annotations;
}
