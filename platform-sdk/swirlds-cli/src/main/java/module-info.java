// SPDX-License-Identifier: Apache-2.0
module org.hiero.consensus.pcli {
    opens org.hiero.consensus.pcli to
            info.picocli;
    opens org.hiero.consensus.pcli.utility to
            info.picocli;
    opens org.hiero.consensus.pcli.graph to
            info.picocli;

    exports org.hiero.consensus.pcli.utility;
    exports org.hiero.consensus.pcli;

    requires com.hedera.node.hapi;
    requires com.hedera.pbj.runtime;
    requires com.swirlds.base;
    requires com.swirlds.common;
    requires com.swirlds.component.framework;
    requires com.swirlds.config.api;
    requires com.swirlds.config.extensions;
    requires com.swirlds.logging;
    requires com.swirlds.metrics.api;
    requires com.swirlds.platform.core;
    requires com.swirlds.state.api;
    requires com.swirlds.state.impl;
    requires com.swirlds.virtualmap;
    requires org.hiero.base.crypto;
    requires org.hiero.base.utility;
    requires org.hiero.consensus.concurrent;
    requires org.hiero.consensus.event.creator;
    requires org.hiero.consensus.event.intake;
    requires org.hiero.consensus.gossip;
    requires org.hiero.consensus.hashgraph.impl;
    requires org.hiero.consensus.hashgraph;
    requires org.hiero.consensus.metrics;
    requires org.hiero.consensus.model;
    requires org.hiero.consensus.pces.impl;
    requires org.hiero.consensus.pces;
    requires org.hiero.consensus.platformstate;
    requires org.hiero.consensus.roster;
    requires org.hiero.consensus.state;
    requires org.hiero.consensus.utility;
    requires info.picocli;
    requires io.github.classgraph;
    requires org.apache.logging.log4j;
    requires static com.github.spotbugs.annotations;
}
