// SPDX-License-Identifier: Apache-2.0
module com.swirlds.demo.stats.signing {
    requires com.hedera.node.hapi;
    requires com.hedera.pbj.runtime;
    requires com.swirlds.base;
    requires com.swirlds.common;
    requires com.swirlds.logging;
    requires com.swirlds.metrics.api;
    requires com.swirlds.platform.core.test.fixtures;
    requires com.swirlds.platform.core;
    requires com.swirlds.state.api;
    requires com.swirlds.state.impl;
    requires org.hiero.consensus.model;
    requires lazysodium.java;
    requires org.apache.logging.log4j;
    requires org.bouncycastle.provider;
    requires static com.github.spotbugs.annotations;
}
