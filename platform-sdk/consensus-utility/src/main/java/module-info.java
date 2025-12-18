// SPDX-License-Identifier: Apache-2.0
module org.hiero.consensus.utility {
    exports org.hiero.consensus.config;
    exports org.hiero.consensus.crypto;
    exports org.hiero.consensus.event;
    exports org.hiero.consensus.exceptions;
    exports org.hiero.consensus.roster;
    exports org.hiero.consensus.transaction;
    exports org.hiero.consensus.round;

    requires transitive com.hedera.node.hapi;
    requires transitive com.hedera.pbj.runtime;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.metrics.api;
    requires transitive org.hiero.base.crypto;
    requires transitive org.hiero.consensus.concurrent;
    requires transitive org.hiero.consensus.metrics;
    requires transitive org.hiero.consensus.model;
    requires com.swirlds.base;
    requires org.hiero.base.utility;
    requires com.goterl.lazysodium;
    requires org.bouncycastle.provider;
    requires static transitive com.github.spotbugs.annotations;
}
