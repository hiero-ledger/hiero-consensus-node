// SPDX-License-Identifier: Apache-2.0
module org.hiero.consensus.utility {
    exports org.hiero.consensus.config;
    exports org.hiero.consensus.crypto;
    exports org.hiero.consensus.exceptions;
    exports org.hiero.consensus.roster;

    requires transitive com.hedera.node.hapi;
    requires transitive com.hedera.pbj.runtime;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.state.api;
    requires transitive org.hiero.base.crypto;
    requires transitive org.hiero.consensus.model;
    requires com.swirlds.base;
    requires com.swirlds.logging;
    requires org.bouncycastle.provider;
    requires static transitive com.github.spotbugs.annotations;
}
