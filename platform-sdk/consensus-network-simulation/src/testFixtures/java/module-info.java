// SPDX-License-Identifier: Apache-2.0
open module org.hiero.consensus.network.simulation.test.fixtures {
    exports org.hiero.consensus.network.simulation;

    requires transitive com.hedera.node.hapi;
    requires transitive com.swirlds.base;
    requires transitive com.swirlds.common;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.metrics.api;
    requires transitive org.hiero.consensus.model;
    requires com.hedera.pbj.runtime;
    requires com.swirlds.base.test.fixtures;
    requires com.swirlds.common.test.fixtures;
    requires com.swirlds.config.extensions.test.fixtures;
    requires org.hiero.base.crypto;
    requires org.hiero.consensus.event.creator;
    requires org.hiero.consensus.event.creator.impl;
    requires org.hiero.consensus.roster.test.fixtures;
    requires org.hiero.consensus.utility;
    requires org.hiero.consensus.utility.test.fixtures;
}
