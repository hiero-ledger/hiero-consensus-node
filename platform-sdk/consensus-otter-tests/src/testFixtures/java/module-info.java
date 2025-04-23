// SPDX-License-Identifier: Apache-2.0
module org.hiero.otter.fixtures {
    requires transitive com.swirlds.logging;
    requires transitive com.swirlds.platform.core.test.fixtures;
    requires transitive com.swirlds.platform.core;
    requires transitive org.apache.logging.log4j.core;
    requires transitive org.junit.jupiter.api;
    requires com.hedera.node.config;
    requires com.hedera.node.hapi;
    requires com.hedera.pbj.runtime;
    requires com.swirlds.base.test.fixtures;
    requires com.swirlds.common.test.fixtures;
    requires com.swirlds.component.framework;
    requires com.swirlds.config.api;
    requires com.swirlds.config.extensions;
    requires com.swirlds.merkledb;
    requires com.swirlds.metrics.api;
    requires com.swirlds.state.api;
    requires org.hiero.base.utility;
    requires org.hiero.consensus.model;
    requires org.apache.logging.log4j;
    requires org.assertj.core;
    requires static com.github.spotbugs.annotations;

    exports org.hiero.otter.fixtures;
    exports org.hiero.otter.fixtures.junit;
}
