// SPDX-License-Identifier: Apache-2.0
module com.swirlds.demo.stats {
    requires com.hedera.node.hapi;
    requires com.hedera.pbj.runtime;
    requires com.swirlds.base;
    requires com.swirlds.common;
    requires com.swirlds.platform.core.test.fixtures;
    requires com.swirlds.platform.core;
    requires com.swirlds.state.api;
    requires com.swirlds.state.impl;
    requires com.swirlds.virtualmap;
    requires org.hiero.base.concurrent;
    requires org.hiero.base.utility;
    requires org.hiero.consensus.model;
    requires java.desktop;
    requires static com.github.spotbugs.annotations;
}
