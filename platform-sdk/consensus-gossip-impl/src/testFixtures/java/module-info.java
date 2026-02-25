// SPDX-License-Identifier: Apache-2.0
open module org.hiero.consensus.gossip.impl.test.fixtures {
    // The test fixtures in this module expose internal classes
    // and can therefore not be used by other modules.
    exports org.hiero.consensus.gossip.impl.test.fixtures.communication to
            org.hiero.consensus.gossip.impl;
    exports org.hiero.consensus.gossip.impl.test.fixtures.communication.multithreaded to
            org.hiero.consensus.gossip.impl;
    exports org.hiero.consensus.gossip.impl.test.fixtures.network to
            org.hiero.consensus.gossip.impl;
    exports org.hiero.consensus.gossip.impl.test.fixtures.sync to
            org.hiero.consensus.gossip.impl;

    requires transitive com.swirlds.base;
    requires transitive com.swirlds.common;
    requires transitive com.swirlds.config.api;
    requires transitive org.hiero.base.crypto;
    requires transitive org.hiero.consensus.gossip.impl;
    requires transitive org.hiero.consensus.model;
    requires com.swirlds.common.test.fixtures;
    requires com.swirlds.config.extensions.test.fixtures;
    requires org.hiero.consensus.model.test.fixtures;
    requires com.github.spotbugs.annotations;
    requires java.desktop;
    requires org.junit.jupiter.api;
}
