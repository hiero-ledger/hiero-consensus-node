// SPDX-License-Identifier: Apache-2.0
module com.swirlds.fcqueue {
    exports com.swirlds.fcqueue;

    requires transitive com.swirlds.common;
    requires transitive com.swirlds.metrics.api;
    requires transitive org.hiero.consensus.model;
    requires transitive org.hiero.consensus.utility;
    requires static transitive com.github.spotbugs.annotations;
}
