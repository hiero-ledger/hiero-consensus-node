// SPDX-License-Identifier: Apache-2.0
open module org.hiero.consensus.integration.tests {
    requires org.hiero.consensus.model;
    requires org.hiero.consensus.hashgraph;
    requires org.hiero.consensus.hashgraph.impl;
    requires org.hiero.consensus.event.creator;
    requires org.hiero.consensus.event.creator.impl;
    requires org.hiero.consensus.roster;
    requires org.hiero.consensus.utility;
    requires org.hiero.consensus.metrics;
    requires com.swirlds.base;
    requires com.swirlds.common;
    requires com.swirlds.config.api;
    requires com.swirlds.config.extensions;
    requires com.swirlds.metrics.api;
    requires org.junit.jupiter.api;
    requires org.junit.jupiter.params;
    requires org.assertj.core;
    requires static com.github.spotbugs.annotations;
    requires org.hiero.consensus.event.intake.impl;
    requires com.swirlds.platform.core;
}
