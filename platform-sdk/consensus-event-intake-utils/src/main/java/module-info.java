// SPDX-License-Identifier: Apache-2.0
module org.hiero.consensus.event.intake.utils {
    exports org.hiero.consensus.event.intake.utils;

    requires transitive com.swirlds.base;
    requires transitive com.swirlds.metrics.api;
    requires transitive org.hiero.base.crypto;
    requires transitive org.hiero.consensus.model;
    requires transitive org.hiero.consensus.roster;
    requires transitive org.hiero.consensus.utility;
    requires com.hedera.node.hapi;
    requires com.hedera.pbj.runtime;
    requires com.swirlds.logging;
    requires org.hiero.consensus.concurrent;
    requires org.hiero.consensus.metrics;
    requires org.apache.logging.log4j;
    requires static transitive com.github.spotbugs.annotations;
}
