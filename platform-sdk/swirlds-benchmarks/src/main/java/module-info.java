// SPDX-License-Identifier: Apache-2.0
module com.swirlds.benchmarks {
    exports com.swirlds.benchmark.reconnect.network;

    requires com.swirlds.config.api;
    requires com.swirlds.metrics.api;
    requires org.hiero.consensus.gossip.impl;
    requires org.hiero.consensus.gossip;
    requires org.hiero.consensus.model;
    requires org.hiero.consensus.utility;
    requires static com.github.spotbugs.annotations;
}
