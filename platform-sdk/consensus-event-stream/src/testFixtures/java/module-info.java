// SPDX-License-Identifier: Apache-2.0
open module org.hiero.consensus.event.stream.test.fixtures {
    exports org.hiero.consensus.event.stream.test.fixtures;

    requires transitive org.hiero.base.crypto;
    requires transitive org.hiero.base.utility;
    requires transitive org.hiero.consensus.event.stream;
    requires transitive org.hiero.consensus.model;
    requires com.swirlds.base.test.fixtures;
    requires com.swirlds.base;
    requires com.swirlds.config.api;
    requires com.swirlds.config.extensions.test.fixtures;
    requires com.swirlds.logging;
    requires org.hiero.base.concurrent;
    requires org.hiero.base.crypto.test.fixtures;
    requires org.hiero.base.utility.test.fixtures;
    requires org.hiero.consensus.model.test.fixtures;
    requires org.hiero.consensus.utility;
    requires org.apache.logging.log4j;
    requires org.mockito;
}
