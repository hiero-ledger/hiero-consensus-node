// SPDX-License-Identifier: Apache-2.0
open module org.hiero.consensus.hashgraph.impl.test.fixtures {
    requires transitive com.hedera.node.hapi;
    requires transitive com.swirlds.base;
    requires transitive com.swirlds.common;
    requires transitive com.swirlds.component.framework;
    requires transitive com.swirlds.config.api;
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
    requires com.swirlds.common.test.fixtures;
    requires com.swirlds.config.extensions.test.fixtures;
    requires com.swirlds.logging;
    requires com.swirlds.metrics.api;
    requires com.swirlds.platform.core;
    requires org.hiero.base.crypto.test.fixtures;
    requires org.hiero.base.utility.test.fixtures;
    requires org.hiero.consensus.hashgraph.impl;
    requires org.hiero.consensus.metrics;
    requires transitive org.hiero.consensus.roster.test.fixtures;
    requires org.hiero.consensus.roster;
    requires java.desktop;
    requires org.apache.logging.log4j;
    requires org.mockito;
    requires static com.github.spotbugs.annotations;

    exports org.hiero.consensus.hashgraph.impl.test.fixtures.consensus;
    exports org.hiero.consensus.hashgraph.impl.test.fixtures.consensus.framework;
    exports org.hiero.consensus.hashgraph.impl.test.fixtures.consensus.framework.validation;
    exports org.hiero.consensus.hashgraph.impl.test.fixtures.event;
    exports org.hiero.consensus.hashgraph.impl.test.fixtures.event.emitter;
    exports org.hiero.consensus.hashgraph.impl.test.fixtures.event.generator;
    exports org.hiero.consensus.hashgraph.impl.test.fixtures.event.source;
    exports org.hiero.consensus.hashgraph.impl.test.fixtures.graph;
    exports org.hiero.consensus.hashgraph.impl.test.fixtures.gui.runner;
    exports org.hiero.consensus.hashgraph.impl.test.fixtures.graph.internal to
            org.hiero.consensus.hashgraph.impl;
}
