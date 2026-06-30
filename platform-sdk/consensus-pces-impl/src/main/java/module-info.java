// SPDX-License-Identifier: Apache-2.0
import org.hiero.consensus.pces.PcesModule;
import org.hiero.consensus.pces.impl.DefaultPcesModule;

module org.hiero.consensus.pces.impl {
    exports org.hiero.consensus.pces.impl to
            org.hiero.consensus.state.management;
    exports org.hiero.consensus.pces.impl.common to
            com.swirlds.platform.core,
            org.hiero.consensus.pcli,
            org.hiero.otter.fixtures,
            org.hiero.sloth.fixtures,
            org.hiero.consensus.pces.impl.test.fixtures;

    requires transitive com.hedera.node.hapi;
    requires transitive com.swirlds.base;
    requires transitive com.swirlds.component.framework;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.metrics.api;
    requires transitive org.hiero.base.utility;
    requires transitive org.hiero.consensus.metrics;
    requires transitive org.hiero.consensus.model;
    requires transitive org.hiero.consensus.pces;
    requires transitive org.hiero.consensus.state;
    requires transitive org.hiero.consensus.utility;
    requires com.hedera.pbj.runtime;
    requires com.swirlds.logging;
    requires org.hiero.base.crypto;
    requires org.hiero.consensus.concurrent;
    requires org.apache.logging.log4j;
    requires static transitive com.github.spotbugs.annotations;

    provides PcesModule with
            DefaultPcesModule;
}
