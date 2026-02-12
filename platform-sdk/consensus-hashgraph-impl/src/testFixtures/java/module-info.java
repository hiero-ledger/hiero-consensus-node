// SPDX-License-Identifier: Apache-2.0
open module org.hiero.consensus.hashgraph.impl.test.fixtures {
    requires transitive com.hedera.node.hapi;
    requires transitive com.swirlds.common;
    requires transitive org.hiero.consensus.model.test.fixtures;
    requires transitive org.hiero.consensus.model;
    requires transitive org.hiero.consensus.utility.test.fixtures;
    requires com.hedera.pbj.runtime;
    requires com.swirlds.base;
    requires com.swirlds.common.test.fixtures;
    requires com.swirlds.config.api;
    requires com.swirlds.metrics.api;
    requires com.swirlds.platform.core;
    requires org.hiero.base.crypto.test.fixtures;
    requires org.hiero.base.crypto;
    requires org.hiero.consensus.hashgraph.impl;
    requires org.hiero.consensus.roster.test.fixtures;
    requires org.mockito;
    requires static com.github.spotbugs.annotations;

    exports org.hiero.consensus.hashgraph.impl.test.fixtures.event;
    exports org.hiero.consensus.hashgraph.impl.test.fixtures.event.emitter;
    exports org.hiero.consensus.hashgraph.impl.test.fixtures.event.generator;
    exports org.hiero.consensus.hashgraph.impl.test.fixtures.event.source;
}
