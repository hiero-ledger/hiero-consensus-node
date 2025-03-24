// SPDX-License-Identifier: Apache-2.0
module org.hiero.event.creator.impl {
    exports org.hiero.event.creator.impl.rules;
    exports org.hiero.event.creator.impl;

    requires transitive com.swirlds.common;
    requires transitive com.swirlds.config.api;
    requires transitive org.hiero.event.creator;
    requires com.swirlds.base;
    requires static transitive com.github.spotbugs.annotations;

    provides org.hiero.event.creator.EventCreator with
            org.hiero.event.creator.impl.EventCreatorImpl;
}
