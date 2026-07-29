// SPDX-License-Identifier: Apache-2.0
module org.hiero.consensus.main.model {
    exports org.hiero.consensus.main.model;

    requires transitive com.hedera.pbj.runtime;
    requires transitive com.hedera.node.hapi;
    requires transitive org.hiero.base.crypto;
    requires static transitive com.github.spotbugs.annotations;
}
