// SPDX-License-Identifier: Apache-2.0
import org.hiero.consensus.event.creator.EventCreatorModule;
import org.hiero.consensus.event.intake.EventIntakeModule;
import org.hiero.consensus.gossip.GossipModule;
import org.hiero.consensus.gossip.impl.reconnect.ProtocolFactory;
import org.hiero.consensus.hashgraph.HashgraphModule;
import org.hiero.consensus.pces.PcesModule;

/**
 * The Swirlds public API module used by platform applications.
 */
module com.swirlds.platform.core {
    uses EventCreatorModule;
    uses EventIntakeModule;
    uses HashgraphModule;
    uses PcesModule;
    uses GossipModule;

    /* Public Package Exports. This list should remain alphabetized. */
    exports com.swirlds.platform;
    exports com.swirlds.platform.builder;
    exports com.swirlds.platform.components;
    exports com.swirlds.platform.components.common.output;
    exports com.swirlds.platform.components.state.output;
    exports com.swirlds.platform.config;
    exports com.swirlds.platform.config.legacy;
    exports com.swirlds.platform.crypto;
    exports com.swirlds.platform.event.report;
    exports com.swirlds.platform.eventhandling;
    exports com.swirlds.platform.health;
    exports com.swirlds.platform.health.clock;
    exports com.swirlds.platform.health.entropy;
    exports com.swirlds.platform.health.filesystem;
    exports com.swirlds.platform.listeners;
    exports com.swirlds.platform.metrics;
    exports com.swirlds.platform.state;
    exports com.swirlds.platform.state.signed;
    exports com.swirlds.platform.state.address;
    exports com.swirlds.platform.scratchpad;
    exports com.swirlds.platform.system;
    exports com.swirlds.platform.system.transaction;
    exports com.swirlds.platform.system.state.notifications;
    exports com.swirlds.platform.system.status;
    exports com.swirlds.platform.system.status.actions;
    exports com.swirlds.platform.util;

    /* Targeted Exports to External Libraries */
    exports com.swirlds.platform.internal to
            org.hiero.consensus.pcli,
            com.swirlds.platform.core.test.fixtures,
            com.fasterxml.jackson.core,
            com.fasterxml.jackson.databind;
    exports com.swirlds.platform.uptime to
            com.swirlds.config.extensions,
            com.swirlds.config.impl,
            com.swirlds.common,
            com.hedera.node.test.clients;
    exports com.swirlds.platform.reconnect;
    exports com.swirlds.platform.event;
    exports com.swirlds.platform.wiring;
    exports com.swirlds.platform.wiring.components;
    exports com.swirlds.platform.state.snapshot;
    exports com.swirlds.platform.state.service.schemas;
    exports com.swirlds.platform.builder.internal;
    exports com.swirlds.platform.config.internal;
    exports com.swirlds.platform.state.iss to
            org.hiero.otter.test;
    exports com.swirlds.platform.recovery.internal to
            org.hiero.consensus.pcli;

    requires transitive com.hedera.node.hapi;
    requires transitive com.hedera.pbj.runtime;
    requires transitive com.swirlds.base;
    requires transitive com.swirlds.common;
    requires transitive com.swirlds.component.framework;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.metrics.api;
    requires transitive com.swirlds.state.api;
    requires transitive com.swirlds.state.impl;
    requires transitive com.swirlds.virtualmap;
    requires transitive org.hiero.base.concurrent;
    requires transitive org.hiero.base.crypto;
    requires transitive org.hiero.base.utility;
    requires transitive org.hiero.consensus.concurrent;
    requires transitive org.hiero.consensus.event.creator;
    requires transitive org.hiero.consensus.event.intake;
    requires transitive org.hiero.consensus.gossip.impl;
    requires transitive org.hiero.consensus.gossip;
    requires transitive org.hiero.consensus.hashgraph;
    requires transitive org.hiero.consensus.metrics;
    requires transitive org.hiero.consensus.model;
    requires transitive org.hiero.consensus.pces;
    requires transitive org.hiero.consensus.reconnect;
    requires transitive org.hiero.consensus.roster;
    requires transitive org.hiero.consensus.state;
    requires transitive org.hiero.consensus.utility;
    requires com.swirlds.config.extensions;
    requires com.swirlds.logging;
    requires com.swirlds.merkledb;
    requires org.hiero.consensus.pces.impl;
    requires org.hiero.consensus.platformstate;
    requires com.github.spotbugs.annotations;
    requires java.management;
    requires java.scripting;
    requires jdk.management;
    requires jdk.net;
    requires org.apache.logging.log4j;
    requires org.bouncycastle.pkix;
    requires org.bouncycastle.provider;

    provides com.swirlds.config.api.ConfigurationExtension with
            com.swirlds.platform.config.PlatformConfigurationExtension;
    provides ProtocolFactory with
            com.swirlds.platform.reconnect.ReconnectProtocolFactory;
}
