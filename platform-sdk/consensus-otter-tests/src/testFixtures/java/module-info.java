// SPDX-License-Identifier: Apache-2.0
module org.hiero.otter.fixtures {
    requires transitive com.hedera.node.hapi;
    requires transitive com.swirlds.base.test.fixtures;
    requires transitive com.swirlds.base;
    requires transitive com.swirlds.common.test.fixtures;
    requires transitive com.swirlds.common;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.logging;
    requires transitive com.swirlds.platform.core.test.fixtures;
    requires transitive com.swirlds.platform.core;
    requires transitive com.swirlds.state.api;
    requires transitive com.swirlds.state.impl;
    requires transitive org.hiero.base.utility;
    requires transitive org.hiero.consensus.model;
    requires transitive com.google.protobuf;
    requires transitive org.apache.logging.log4j.core;
    requires transitive org.apache.logging.log4j;
    requires transitive org.assertj.core;
    requires transitive org.junit.jupiter.api;
    requires com.hedera.node.app.hapi.utils;
    requires com.hedera.node.config;
    requires com.hedera.pbj.runtime;
    requires com.swirlds.component.framework;
    requires com.swirlds.config.extensions;
    requires com.swirlds.merkledb;
    requires com.swirlds.metrics.api;
    requires org.hiero.consensus.utility;
    requires org.junit.jupiter.params;
    requires static com.github.spotbugs.annotations;

    exports org.hiero.otter.fixtures;
    exports org.hiero.otter.fixtures.assertions;
    exports org.hiero.otter.fixtures.junit;
    exports org.hiero.otter.fixtures.logging;
    exports org.hiero.otter.fixtures.result;
    exports org.hiero.otter.fixtures.turtle;
    exports org.hiero.otter.fixtures.turtle.app;
}
