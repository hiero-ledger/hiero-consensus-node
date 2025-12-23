// SPDX-License-Identifier: Apache-2.0
module org.hiero.consensus.pces {
    exports org.hiero.consensus.pces;

    requires transitive com.hedera.node.hapi;
    requires transitive com.swirlds.base;
    requires transitive com.swirlds.common;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.metrics.api;
    requires transitive org.hiero.consensus.metrics;
    requires transitive org.hiero.consensus.model;
    requires com.hedera.pbj.runtime;
    requires com.swirlds.logging;
    requires org.hiero.base.crypto;
    requires org.hiero.base.utility;
    requires org.apache.logging.log4j;
    requires static transitive com.github.spotbugs.annotations;
}
