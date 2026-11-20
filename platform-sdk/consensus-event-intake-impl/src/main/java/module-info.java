// SPDX-License-Identifier: Apache-2.0
import org.hiero.consensus.event.intake.EventIntakeModule;
import org.hiero.consensus.event.intake.impl.DefaultEventIntakeModule;

// SPDX-License-Identifier: Apache-2.0
module org.hiero.consensus.event.intake.impl {
    exports org.hiero.consensus.event.intake.impl.deduplication to
            org.hiero.otter.fixtures;

    requires transitive com.swirlds.metrics.api;
    requires transitive org.hiero.consensus.event.intake;
    requires transitive org.hiero.consensus.model;
    requires transitive org.hiero.consensus.utility;
    requires transitive org.hiero.consensus.wiring.framework;
    requires com.hedera.pbj.runtime;
    requires com.swirlds.base;
    requires com.swirlds.config.api;
    requires com.swirlds.logging;
    requires org.hiero.base.concurrent;
    requires org.hiero.base.crypto;
    requires org.hiero.base.utility;
    requires org.hiero.consensus.metrics;
    requires org.apache.logging.log4j;
    requires static transitive com.github.spotbugs.annotations;

    provides EventIntakeModule with
            DefaultEventIntakeModule;
}
