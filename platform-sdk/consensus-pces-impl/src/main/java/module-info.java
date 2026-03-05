// SPDX-License-Identifier: Apache-2.0
import org.hiero.consensus.pces.PcesModule;
import org.hiero.consensus.pces.impl.DefaultPcesModule;

module org.hiero.consensus.pces.impl {
    exports org.hiero.consensus.pces.impl.common to
            com.swirlds.platform.core,
            com.swirlds.platform.core.test.fixtures,
            org.hiero.consensus.pcli,
            org.hiero.otter.fixtures,
            org.hiero.consensus.pces.impl.test.fixtures;

    requires transitive com.hedera.node.hapi;
    requires transitive com.swirlds.base;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.metrics.api;
    requires transitive org.hiero.consensus.metrics;
    requires transitive org.hiero.consensus.model;
    requires transitive org.hiero.consensus.pces;
    requires transitive org.hiero.consensus.utility;
    requires com.hedera.pbj.runtime;
    requires com.swirlds.common;
    requires com.swirlds.component.framework;
    requires com.swirlds.logging;
    requires org.hiero.base.crypto;
    requires org.hiero.base.utility;
    requires org.hiero.consensus.concurrent;
    requires org.hiero.consensus.state;
    requires org.apache.logging.log4j;
    requires static transitive com.github.spotbugs.annotations;

    provides PcesModule with
            DefaultPcesModule;
}
