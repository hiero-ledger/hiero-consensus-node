// SPDX-License-Identifier: Apache-2.0
module com.swirlds.demo.crypto {
    requires com.hedera.node.hapi;
    requires com.hedera.pbj.runtime;
    requires com.swirlds.common;
    requires com.swirlds.metrics.api;
    requires com.swirlds.platform.core.test.fixtures;
    requires com.swirlds.platform.core;
    requires com.swirlds.state.api;
    requires com.swirlds.state.impl;
    requires org.hiero.consensus.model;
    requires java.desktop;
    requires org.apache.logging.log4j;
    requires static com.github.spotbugs.annotations;
}
