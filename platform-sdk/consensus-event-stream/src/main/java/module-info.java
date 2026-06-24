// SPDX-License-Identifier: Apache-2.0
import com.swirlds.config.api.ConfigurationExtension;
import org.hiero.consensus.event.stream.config.EventStreamConfigurationExtension;

module org.hiero.consensus.event.stream {
    exports org.hiero.consensus.event.stream;
    exports org.hiero.consensus.event.stream.config;

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

    provides ConfigurationExtension with
            EventStreamConfigurationExtension;

    opens org.hiero.consensus.event.stream to
            com.fasterxml.jackson.databind;
    opens org.hiero.consensus.event.stream.internal to
            com.fasterxml.jackson.databind;

    exports org.hiero.consensus.event.stream.internal;
}
