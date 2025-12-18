// SPDX-License-Identifier: Apache-2.0
module org.hiero.consensus.utility {
    exports org.hiero.consensus.config;
    exports org.hiero.consensus.crypto;
    exports org.hiero.consensus.event;
    exports org.hiero.consensus.exceptions;
    exports org.hiero.consensus.roster;
    exports org.hiero.consensus.threading.framework;
    exports org.hiero.consensus.threading.framework.config;
    exports org.hiero.consensus.threading.framework.internal;
    exports org.hiero.consensus.threading.manager;
    exports org.hiero.consensus.threading.pool;
    exports org.hiero.consensus.transaction;
    exports org.hiero.consensus.round;

    requires transitive com.hedera.node.hapi;
    requires transitive com.hedera.pbj.runtime;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.metrics.api;
    requires transitive com.swirlds.state.api;
    requires transitive org.hiero.base.crypto;
    requires transitive org.hiero.consensus.model;
    requires com.swirlds.base;
    requires com.swirlds.common;
    requires org.hiero.base.utility;
    requires com.goterl.lazysodium;
    requires org.bouncycastle.provider;
    requires static transitive com.github.spotbugs.annotations;
}
