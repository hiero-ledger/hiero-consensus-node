// SPDX-License-Identifier: Apache-2.0
open module org.hiero.consensus.network.simulation.test.fixtures {
    exports org.hiero.consensus.network.simulation.fixtures;

    requires transitive com.hedera.node.hapi;
    requires transitive com.swirlds.common;
    requires transitive com.swirlds.config.api;
    requires transitive org.hiero.consensus.model;
    requires com.hedera.pbj.runtime;
    requires com.swirlds.base.test.fixtures;
    requires com.swirlds.base;
    requires com.swirlds.common.test.fixtures;
    requires com.swirlds.metrics.api;
    requires org.hiero.base.crypto;
    requires org.hiero.consensus.event.creator.impl;
    requires org.hiero.consensus.roster.test.fixtures;
    requires org.hiero.consensus.utility.test.fixtures;
    requires org.hiero.consensus.utility;
}
