// SPDX-License-Identifier: Apache-2.0
module org.hiero.consensus.roster {
    exports org.hiero.consensus.roster;

    requires transitive com.hedera.node.hapi;
    requires transitive com.hedera.pbj.runtime;
    requires transitive com.swirlds.state.api;
    requires transitive org.hiero.consensus.utility;
    requires com.hedera.hapi.platform.state;
    requires com.swirlds.base;
    requires com.swirlds.state.lifecycle;
    requires org.hiero.base.crypto;
    requires org.hiero.base.utility;
    requires org.hiero.consensus.model;
    requires static transitive com.github.spotbugs.annotations;
}
