// SPDX-License-Identifier: Apache-2.0
module org.hiero.consensus.model {
    exports org.hiero.consensus.model.event;
    exports org.hiero.consensus.model.hashgraph;
    exports org.hiero.consensus.model.node;
    exports org.hiero.consensus.model.notification;
    exports org.hiero.consensus.model.roster;
    exports org.hiero.consensus.model.sequence;
    exports org.hiero.consensus.model.sequence.map;
    exports org.hiero.consensus.model.sequence.set;
    exports org.hiero.consensus.model.state;
    exports org.hiero.consensus.model.status;
    exports org.hiero.consensus.model.stream;
    exports org.hiero.consensus.model.transaction;

    requires transitive com.hedera.node.hapi;
    requires transitive com.hedera.pbj.runtime;
    requires transitive org.hiero.base.crypto;
    requires transitive org.hiero.base.utility;
    requires com.swirlds.base;
    requires org.hiero.base.concurrent;
    requires static transitive com.github.spotbugs.annotations;
}
