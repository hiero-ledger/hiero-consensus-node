// SPDX-License-Identifier: Apache-2.0
module org.hiero.consensus.event.creator {
    exports org.hiero.consensus.event.creator;

    requires transitive com.hedera.pbj.runtime;
    requires transitive com.swirlds.base;
    requires transitive com.swirlds.component.framework;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.metrics.api;
    requires transitive org.hiero.consensus.model;
    requires static transitive com.github.spotbugs.annotations;
}
