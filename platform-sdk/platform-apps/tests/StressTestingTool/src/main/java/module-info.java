// SPDX-License-Identifier: Apache-2.0
module com.swirlds.demo.stress {
    requires com.hedera.node.hapi;
    requires com.hedera.pbj.runtime;
    requires com.swirlds.base;
    requires com.swirlds.common;
    requires com.swirlds.config.api;
    requires com.swirlds.logging;
    requires com.swirlds.metrics.api;
    requires com.swirlds.platform.core.test.fixtures;
    requires com.swirlds.platform.core;
    requires com.swirlds.state.api;
    requires com.swirlds.state.impl;
    requires org.hiero.consensus.model;
    requires org.apache.logging.log4j;
    requires static com.github.spotbugs.annotations;
}
