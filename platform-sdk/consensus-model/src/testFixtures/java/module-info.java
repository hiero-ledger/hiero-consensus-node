// SPDX-License-Identifier: Apache-2.0
open module org.hiero.consensus.model.test.fixtures {
    exports org.hiero.consensus.model.test.fixtures.event;
    exports org.hiero.consensus.model.test.fixtures.hashgraph;
    exports org.hiero.consensus.model.test.fixtures.transaction;

    requires transitive com.hedera.pbj.runtime;
    requires transitive org.hiero.consensus.model;
    requires com.hedera.node.hapi;
    requires org.hiero.base.crypto.test.fixtures;
    requires org.hiero.base.utility.test.fixtures;
    requires static transitive com.github.spotbugs.annotations;
}
