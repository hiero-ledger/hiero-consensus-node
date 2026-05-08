// SPDX-License-Identifier: Apache-2.0
module org.hiero.consensus.event.stream {
    exports org.hiero.consensus.event.stream;
    exports org.hiero.consensus.event.stream.internal to
            org.hiero.consensus.event.stream.test.fixtures;

    requires transitive com.swirlds.base;
    requires transitive com.swirlds.component.framework;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.metrics.api;
    requires transitive org.hiero.base.crypto;
    requires transitive org.hiero.base.utility;
    requires transitive org.hiero.consensus.concurrent;
    requires transitive org.hiero.consensus.model;
    requires com.hedera.pbj.runtime;
    requires com.swirlds.logging;
    requires org.hiero.base.concurrent;
    requires org.hiero.consensus.metrics;
    requires org.hiero.consensus.utility;
    requires org.apache.logging.log4j;
    requires static transitive com.github.spotbugs.annotations;

    opens org.hiero.consensus.event.stream to
            com.fasterxml.jackson.databind;
    opens org.hiero.consensus.event.stream.internal to
            com.fasterxml.jackson.databind;
}
