// SPDX-License-Identifier: Apache-2.0
module org.hiero.otter.fixtures {
    requires transitive com.swirlds.logging;
    requires transitive org.apache.logging.log4j.core;
    requires transitive org.junit.jupiter.api;
    requires com.swirlds.base.test.fixtures;
    requires com.swirlds.common.test.fixtures;
    requires com.swirlds.platform.core.test.fixtures;
    requires com.swirlds.platform.core;
    requires org.hiero.base.utility;
    requires org.hiero.consensus.model;
    requires com.github.spotbugs.annotations;
    requires org.apache.logging.log4j;

    exports org.hiero.otter.fixtures;
    exports org.hiero.otter.fixtures.junit;
}
