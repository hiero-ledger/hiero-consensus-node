// SPDX-License-Identifier: Apache-2.0
open module org.hiero.consensus.event.stream.test.fixtures {
    exports org.hiero.consensus.event.stream.test.fixtures;

    requires transitive org.hiero.base.crypto;
    requires transitive org.hiero.base.utility;
    requires transitive org.hiero.consensus.event.stream;
    requires transitive org.hiero.consensus.model;
    requires com.swirlds.logging;
    requires org.hiero.base.concurrent;
    requires org.hiero.consensus.concurrent;
    requires org.apache.logging.log4j;
}
