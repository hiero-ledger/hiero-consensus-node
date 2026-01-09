// SPDX-License-Identifier: Apache-2.0
import org.hiero.consensus.event.intake.EventIntakeModule;
import org.hiero.consensus.event.intake.impl.DefaultEventIntakeModule;

// SPDX-License-Identifier: Apache-2.0
module org.hiero.consensus.event.intake.impl {
    requires transitive com.swirlds.base;
    requires transitive com.swirlds.component.framework;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.metrics.api;
    requires transitive org.hiero.consensus.event.intake;
    requires transitive org.hiero.consensus.metrics;
    requires transitive org.hiero.consensus.model;
    requires transitive org.hiero.consensus.roster;
    requires transitive org.hiero.consensus.utility;
    requires com.hedera.node.hapi;
    requires com.hedera.pbj.runtime;
    requires com.swirlds.logging;
    requires org.hiero.base.crypto;
    requires org.hiero.consensus.concurrent;
    requires org.apache.logging.log4j;
    requires static transitive com.github.spotbugs.annotations;

    provides EventIntakeModule with
            DefaultEventIntakeModule;
}
