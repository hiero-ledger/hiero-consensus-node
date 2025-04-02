// SPDX-License-Identifier: Apache-2.0
module org.hiero.consensus.event.creator.impl {
    exports org.hiero.consensus.event.creator.impl.rules;
    exports org.hiero.consensus.event.creator.impl;

    requires transitive com.swirlds.common;
    requires transitive com.swirlds.config.api;
    requires transitive org.hiero.consensus.event.creator;
    requires com.swirlds.base;
    requires static transitive com.github.spotbugs.annotations;

    provides org.hiero.consensus.event.creator.EventCreator with
            org.hiero.consensus.event.creator.impl.EventCreatorImpl;
}
