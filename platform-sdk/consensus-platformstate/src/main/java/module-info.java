// SPDX-License-Identifier: Apache-2.0
module org.hiero.consensus.platformstate {
    exports org.hiero.consensus.platformstate;

    requires transitive com.hedera.node.hapi;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.state.api;
    requires transitive org.hiero.base.crypto;
    requires transitive org.hiero.consensus.model;
    requires com.hedera.pbj.runtime;
    requires com.swirlds.base;
    requires com.swirlds.common;
    requires static transitive com.github.spotbugs.annotations;
}
