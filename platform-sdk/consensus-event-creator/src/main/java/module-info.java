// SPDX-License-Identifier: Apache-2.0
module org.hiero.consensus.event.creator {
    exports org.hiero.consensus.event.creator;

    requires transitive com.hedera.pbj.runtime;
    requires transitive com.swirlds.common;
    requires transitive com.swirlds.component.framework;
    requires transitive org.hiero.consensus.model;
    requires static transitive com.github.spotbugs.annotations;
}
