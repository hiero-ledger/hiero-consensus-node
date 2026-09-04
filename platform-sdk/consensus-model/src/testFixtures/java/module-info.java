// SPDX-License-Identifier: Apache-2.0
open module org.hiero.consensus.model.test.fixtures {
    exports org.hiero.consensus.model.test.fixtures.event;
    exports org.hiero.consensus.model.test.fixtures.hashgraph;
    exports org.hiero.consensus.model.test.fixtures.roster;
    exports org.hiero.consensus.model.test.fixtures.transaction;

    requires transitive com.hedera.pbj.runtime;
    requires transitive org.hiero.consensus.model;
    requires transitive org.hiero.consensus.utility.test.fixtures;
    requires transitive com.hedera.node.hapi;
    requires org.hiero.consensus.roster.test.fixtures;
    requires org.hiero.base.crypto.test.fixtures;
    requires org.hiero.base.utility.test.fixtures;
    requires static transitive com.github.spotbugs.annotations;
}
