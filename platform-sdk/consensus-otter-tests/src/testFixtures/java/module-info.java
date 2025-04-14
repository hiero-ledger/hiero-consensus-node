// SPDX-License-Identifier: Apache-2.0
module org.hiero.otter.fixtures {
    requires transitive com.swirlds.base.test.fixtures;
    requires transitive com.swirlds.base;
    requires transitive com.swirlds.common.test.fixtures;
    requires transitive com.swirlds.logging;
    requires transitive com.swirlds.platform.core.test.fixtures;
    requires transitive com.swirlds.platform.core;
    requires transitive org.hiero.consensus.model;
    requires transitive org.apache.logging.log4j.core;
    requires transitive org.junit.jupiter.api;
    requires org.hiero.base.utility;
    requires com.github.spotbugs.annotations;
    requires org.apache.logging.log4j;

    exports org.hiero.otter.fixtures;
    exports org.hiero.otter.fixtures.junit;
    exports org.hiero.otter.fixtures.turtle;
}
