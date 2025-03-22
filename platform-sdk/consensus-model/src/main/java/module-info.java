// SPDX-License-Identifier: Apache-2.0
module org.hiero.consensus.model {
    exports org.hiero.consensus.model.constructable;
    exports org.hiero.consensus.model.crypto;
    exports org.hiero.consensus.model.io;
    exports org.hiero.consensus.model.io.exceptions;
    exports org.hiero.consensus.model.io.streams;
    exports org.hiero.consensus.model.platform;
    exports org.hiero.consensus.model.stream;
    exports org.hiero.consensus.model.system;
    exports org.hiero.consensus.model.system.events;
    exports org.hiero.consensus.model.system.transaction;
    exports org.hiero.consensus.model.threading.futures;
    exports org.hiero.consensus.model.utility;

    requires transitive com.hedera.node.hapi;
    requires transitive com.hedera.pbj.runtime;
    requires com.swirlds.logging;
    requires org.apache.logging.log4j;
    requires static transitive com.github.spotbugs.annotations;
}
