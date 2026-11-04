// SPDX-License-Identifier: Apache-2.0
import org.hiero.consensus.event.creator.EventCreatorModule;
import org.hiero.consensus.event.creator.instrumented.test.fixtures.InstrumentedEventCreatorModule;

module org.hiero.consensus.event.creator.instrumented.test.fixtures {
    exports org.hiero.consensus.event.creator.instrumented.test.fixtures;

    requires transitive com.swirlds.base;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.metrics.api;
    requires transitive org.hiero.consensus.event.creator;
    requires transitive org.hiero.consensus.model;
    requires transitive org.hiero.consensus.wiring.framework;
    requires com.swirlds.logging;
    requires org.hiero.consensus.event.creator.impl;
    requires org.hiero.consensus.utility.test.fixtures;
    requires org.apache.logging.log4j;
    requires static transitive com.github.spotbugs.annotations;

    provides EventCreatorModule with
            InstrumentedEventCreatorModule;
}
