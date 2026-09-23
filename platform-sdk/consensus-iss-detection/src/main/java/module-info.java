// SPDX-License-Identifier: Apache-2.0
import com.swirlds.config.api.ConfigurationExtension;
import org.hiero.consensus.iss.detection.config.IssDetectionConfigurationExtension;

module org.hiero.consensus.iss.detection {
    exports org.hiero.consensus.iss.detection.config;
    exports org.hiero.consensus.iss.detection;
    exports org.hiero.consensus.iss.detection.internal to
            org.hiero.otter.fixtures;

    requires transitive com.hedera.node.hapi;
    requires transitive com.swirlds.base;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.metrics.api;
    requires transitive org.hiero.base.crypto;
    requires transitive org.hiero.base.utility;
    requires transitive org.hiero.consensus.model;
    requires transitive org.hiero.consensus.state;
    requires transitive org.hiero.consensus.utility;
    requires transitive org.hiero.consensus.wiring.framework;
    requires com.hedera.pbj.runtime;
    requires com.swirlds.logging;
    requires com.swirlds.state.impl;
    requires org.hiero.base.concurrent;
    requires org.hiero.consensus.hashgraph;
    requires org.hiero.consensus.pces;
    requires org.hiero.consensus.roster;
    requires org.apache.logging.log4j;
    requires static transitive com.github.spotbugs.annotations;

    provides ConfigurationExtension with
            IssDetectionConfigurationExtension;
}
