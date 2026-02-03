// SPDX-License-Identifier: Apache-2.0
open module org.hiero.consensus.roster.test.fixtures {
    exports org.hiero.consensus.roster.test.fixtures;

    requires transitive org.hiero.consensus.roster;
    requires transitive org.hiero.consensus.utility.test.fixtures;
    requires com.hedera.node.hapi;
    requires com.hedera.pbj.runtime;
    requires org.hiero.base.utility.test.fixtures;
    requires org.hiero.consensus.model;
    requires org.hiero.consensus.utility;
    requires static transitive com.github.spotbugs.annotations;
}
