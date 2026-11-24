// SPDX-License-Identifier: Apache-2.0
module org.hiero.consensus.adapter {
    exports org.hiero.consensus.adapter.transaction;

    requires transitive com.hedera.pbj.runtime;
    requires transitive com.swirlds.metrics.api;
    requires transitive org.hiero.consensus.main.model;
    requires transitive org.hiero.consensus.model;
    requires org.hiero.base.utility;
    requires org.hiero.consensus.metrics;
    requires static transitive com.github.spotbugs.annotations;
}
