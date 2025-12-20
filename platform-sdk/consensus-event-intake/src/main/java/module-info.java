// SPDX-License-Identifier: Apache-2.0
module org.hiero.consensus.event.intake {
    exports org.hiero.consensus.event.intake;
    exports org.hiero.consensus.event.intake.config;

    requires transitive com.swirlds.base;
    requires transitive com.swirlds.common;
    requires transitive com.swirlds.component.framework;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.metrics.api;
    requires transitive org.hiero.consensus.model;
    requires transitive org.hiero.consensus.utility;
    requires static transitive com.github.spotbugs.annotations;
}
