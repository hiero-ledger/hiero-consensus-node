// SPDX-License-Identifier: Apache-2.0
module org.hiero.consensus.event.creator.impl {
    exports org.hiero.consensus.event.creator.impl.config;
    exports org.hiero.consensus.event.creator.impl.rules;
    exports org.hiero.consensus.event.creator.impl;

    requires transitive com.swirlds.common;
    requires transitive com.swirlds.component.framework;
    requires transitive com.swirlds.config.api;
    requires transitive org.hiero.consensus.event.creator;
    requires transitive org.hiero.consensus.model;
    requires com.swirlds.base;
    requires org.hiero.base.utility;
    requires static transitive com.github.spotbugs.annotations;

    provides org.hiero.consensus.event.creator.ConsensusEventCreator with
            org.hiero.consensus.event.creator.impl.ConsensusEventCreatorImpl;
}
