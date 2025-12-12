// SPDX-License-Identifier: Apache-2.0
import org.hiero.consensus.event.intake.EventIntakeModule;
import org.hiero.consensus.event.intake.impl.DefaultEventIntakeModule;

// SPDX-License-Identifier: Apache-2.0
module org.hiero.consensus.event.intake.impl {
    requires transitive com.swirlds.base;
    requires transitive com.swirlds.common;
    requires transitive com.swirlds.component.framework;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.metrics.api;
    requires transitive org.hiero.consensus.event.intake;
    requires transitive org.hiero.consensus.model;
    requires transitive org.hiero.consensus.utility;
    requires static transitive com.github.spotbugs.annotations;

    provides EventIntakeModule with
            DefaultEventIntakeModule;
}
