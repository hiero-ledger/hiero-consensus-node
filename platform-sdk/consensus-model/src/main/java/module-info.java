// SPDX-License-Identifier: Apache-2.0
module org.hiero.consensus.model {
    exports org.hiero.consensus.model.constructable;
    exports org.hiero.consensus.model.crypto;
    exports org.hiero.consensus.model.event;
    exports org.hiero.consensus.model.hashgraph;
    exports org.hiero.consensus.model.io;
    exports org.hiero.consensus.model.io.exceptions;
    exports org.hiero.consensus.model.io.streams;
    exports org.hiero.consensus.model.node;
    exports org.hiero.consensus.model.notification;
    exports org.hiero.consensus.model.state;
    exports org.hiero.consensus.model.status;
    exports org.hiero.consensus.model.stream;
    exports org.hiero.consensus.model.transaction;
    exports org.hiero.consensus.model.utility;
    exports org.hiero.consensus.model.utility.interrupt;

    requires transitive com.hedera.node.hapi;
    requires transitive com.hedera.pbj.runtime;
    requires transitive com.swirlds.base;
    requires com.swirlds.logging;
    requires org.apache.logging.log4j;
    requires static transitive com.github.spotbugs.annotations;
}
