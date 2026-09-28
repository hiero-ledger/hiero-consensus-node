// SPDX-License-Identifier: Apache-2.0
open module org.hiero.consensus.hashgraph.impl.test.fixtures {
    requires transitive com.hedera.node.hapi;
    requires transitive com.swirlds.base;
    requires transitive org.hiero.consensus.wiring.framework;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.metrics.api;
    requires transitive org.hiero.base.crypto;
    requires transitive org.hiero.base.utility;
    requires transitive org.hiero.consensus.hashgraph;
    requires transitive org.hiero.consensus.model.test.fixtures;
    requires transitive org.hiero.consensus.model;
    requires transitive org.hiero.consensus.utility.test.fixtures;
    requires transitive org.hiero.consensus.utility;
    requires transitive org.assertj.core;
    requires com.hedera.pbj.runtime;
    requires com.swirlds.base.test.fixtures;
    requires com.swirlds.config.extensions.test.fixtures;
    requires com.swirlds.platform.core;
    requires org.hiero.base.crypto.test.fixtures;
    requires org.hiero.base.utility.test.fixtures;
    requires org.hiero.consensus.hashgraph.impl;
    requires org.hiero.consensus.fakes;
    requires org.mockito;
    requires static com.github.spotbugs.annotations;

    exports org.hiero.consensus.hashgraph.impl.test.fixtures.consensus;
    exports org.hiero.consensus.hashgraph.impl.test.fixtures.consensus.framework;
    exports org.hiero.consensus.hashgraph.impl.test.fixtures.consensus.framework.validation;
    exports org.hiero.consensus.hashgraph.impl.test.fixtures.event;
    exports org.hiero.consensus.hashgraph.impl.test.fixtures.event.emitter;
    exports org.hiero.consensus.hashgraph.impl.test.fixtures.event.generator;
    exports org.hiero.consensus.hashgraph.impl.test.fixtures.event.source;
    // The Flicker harness exposes EventImpl, so this export must stay qualified: an unqualified one fails the
    // [exports] lint under -Werror, and the only way to satisfy that lint would be to require the impl module
    // transitively - which is what modularization rule 4 forbids test fixtures from doing.
    exports org.hiero.consensus.hashgraph.impl.test.fixtures.flicker to
            org.hiero.consensus.hashgraph.impl;
    exports org.hiero.consensus.hashgraph.impl.test.fixtures.graph;
    exports org.hiero.consensus.hashgraph.impl.test.fixtures.graph.internal to
            org.hiero.consensus.hashgraph.impl;
}
