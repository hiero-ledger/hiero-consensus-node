// SPDX-License-Identifier: Apache-2.0
open module org.hiero.consensus.state.test.fixtures {
    exports org.hiero.consensus.state.test.fixtures;
    exports org.hiero.consensus.state.test.fixtures.manager;

    requires transitive com.hedera.node.hapi;
    requires transitive com.hedera.pbj.runtime;
    requires transitive com.swirlds.state.impl;
    requires transitive org.hiero.base.crypto;
    requires transitive org.hiero.base.utility;
    requires transitive org.hiero.consensus.model;
    requires transitive org.hiero.consensus.state;
    requires com.swirlds.config.api;
    requires com.swirlds.config.extensions.test.fixtures;
    requires com.swirlds.merkledb.test.fixtures;
    requires com.swirlds.state.api;
    requires com.swirlds.state.impl.test.fixtures;
    requires com.swirlds.virtualmap;
    requires org.hiero.base.crypto.test.fixtures;
    requires org.hiero.base.utility.test.fixtures;
    requires org.hiero.consensus.platformstate;
    requires org.hiero.consensus.roster.test.fixtures;
    requires org.hiero.consensus.utility.test.fixtures;
    requires com.github.spotbugs.annotations;
    requires org.mockito;
}
