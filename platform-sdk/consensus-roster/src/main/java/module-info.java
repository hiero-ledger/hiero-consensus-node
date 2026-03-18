// SPDX-License-Identifier: Apache-2.0
module org.hiero.consensus.roster {
    exports org.hiero.consensus.roster;

    requires transitive com.hedera.node.hapi;
    requires transitive com.hedera.pbj.runtime;
    requires transitive com.swirlds.state.api;
    requires transitive org.hiero.base.crypto;
    requires transitive org.hiero.consensus.model;
    requires com.swirlds.base;
    requires org.hiero.base.utility;
    requires static transitive com.github.spotbugs.annotations;
}
