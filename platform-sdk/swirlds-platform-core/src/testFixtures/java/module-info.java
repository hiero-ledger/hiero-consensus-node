// SPDX-License-Identifier: Apache-2.0
open module com.swirlds.platform.core.test.fixtures {
    exports com.swirlds.platform.test.fixtures.builder;

    requires transitive com.hedera.node.hapi;
    requires transitive com.swirlds.base;
    requires transitive com.swirlds.component.framework;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.metrics.api;
    requires transitive com.swirlds.state.api;
    requires transitive com.swirlds.state.impl;
    requires transitive com.swirlds.virtualmap;
    requires transitive org.hiero.consensus.model;
    requires transitive org.hiero.consensus.roster;
    requires transitive org.hiero.consensus.state;
    requires transitive org.hiero.base.utility;
    requires transitive org.hiero.consensus.utility;
    requires com.swirlds.platform.core;
    requires static transitive com.github.spotbugs.annotations;
}
