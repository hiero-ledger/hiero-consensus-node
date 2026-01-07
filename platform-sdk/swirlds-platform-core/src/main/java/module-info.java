// SPDX-License-Identifier: Apache-2.0
import org.hiero.consensus.event.creator.EventCreatorModule;
import org.hiero.consensus.event.intake.EventIntakeModule;

/**
 * The Swirlds public API module used by platform applications.
 */
module com.swirlds.platform.core {
    uses EventCreatorModule;
    uses EventIntakeModule;
    uses org.hiero.consensus.hashgraph.HashgraphModule;

    /* Public Package Exports. This list should remain alphabetized. */
    exports com.swirlds.platform;
    exports com.swirlds.platform.builder;
    exports com.swirlds.platform.network.communication.handshake;
    exports com.swirlds.platform.cli;
    exports com.swirlds.platform.components;
    exports com.swirlds.platform.components.appcomm;
    exports com.swirlds.platform.components.common.output;
    exports com.swirlds.platform.components.state.output;
    exports com.swirlds.platform.config;
    exports com.swirlds.platform.config.legacy;
    exports com.swirlds.platform.crypto;
    exports com.swirlds.platform.event.report;
    exports com.swirlds.platform.gui.hashgraph;
    exports com.swirlds.platform.gui.hashgraph.internal;
    exports com.swirlds.platform.network.connection;
    exports com.swirlds.platform.network.connectivity;
    exports com.swirlds.platform.event.validation;
    exports com.swirlds.platform.eventhandling;
    exports com.swirlds.platform.gui;
    exports com.swirlds.platform.gui.model;
    exports com.swirlds.platform.health;
    exports com.swirlds.platform.health.clock;
    exports com.swirlds.platform.health.entropy;
    exports com.swirlds.platform.health.filesystem;
    exports com.swirlds.platform.listeners;
    exports com.swirlds.platform.metrics;
    exports com.swirlds.platform.network;
    exports com.swirlds.platform.network.communication;
    exports com.swirlds.platform.network.protocol;
    exports com.swirlds.platform.network.topology;
    exports com.swirlds.platform.state;
    exports com.swirlds.platform.state.signed;
    exports com.swirlds.platform.state.address;
    exports com.swirlds.platform.gossip.sync;
    exports com.swirlds.platform.scratchpad;
    exports com.swirlds.platform.system;
    exports com.swirlds.platform.system.address;
    exports com.swirlds.platform.system.transaction;
    exports com.swirlds.platform.system.state.notifications;
    exports com.swirlds.platform.system.status;
    exports com.swirlds.platform.system.status.actions;
    exports com.swirlds.platform.util;
    exports com.swirlds.platform.gossip.config;

    /* Targeted Exports to External Libraries */
    exports com.swirlds.platform.internal to
            com.swirlds.platform.core.test.fixtures,
            com.fasterxml.jackson.core,
            com.fasterxml.jackson.databind;
    exports com.swirlds.platform.uptime to
            com.swirlds.config.extensions,
            com.swirlds.config.impl,
            com.swirlds.common,
            com.hedera.node.test.clients;
    exports com.swirlds.platform.gossip.sync.config to
            com.swirlds.config.extensions,
            com.swirlds.config.impl,
            com.swirlds.common,
            com.swirlds.platform.core.test.fixtures,
            com.hedera.node.test.clients;

    opens com.swirlds.platform.cli to
            info.picocli;

    exports com.swirlds.platform.event.preconsensus;
    exports com.swirlds.platform.gossip.sync.protocol;
    exports com.swirlds.platform.gossip;
    exports com.swirlds.platform.reconnect;
    exports com.swirlds.platform.gossip.shadowgraph;
    exports com.swirlds.platform.event;
    exports com.swirlds.platform.wiring;
    exports com.swirlds.platform.wiring.components;
    exports com.swirlds.platform.state.snapshot;
    exports com.swirlds.platform.state.service.schemas;
    exports com.swirlds.platform.state.service;
    exports com.swirlds.platform.builder.internal;
    exports com.swirlds.platform.config.internal;
    exports com.swirlds.platform.network.protocol.rpc;
    exports com.swirlds.platform.state.iss to
            org.hiero.otter.test;

    requires transitive com.hedera.node.hapi;
    requires transitive com.hedera.pbj.runtime;
    requires transitive com.swirlds.base;
    requires transitive com.swirlds.cli;
    requires transitive com.swirlds.common;
    requires transitive com.swirlds.component.framework;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.metrics.api;
    requires transitive com.swirlds.state.api;
    requires transitive org.hiero.base.concurrent;
    requires transitive org.hiero.base.crypto;
    requires transitive org.hiero.base.utility;
    requires transitive org.hiero.consensus.concurrent;
    requires transitive org.hiero.consensus.event.creator;
    requires transitive org.hiero.consensus.event.intake;
    requires transitive org.hiero.consensus.hashgraph;
    requires transitive org.hiero.consensus.metrics;
    requires transitive org.hiero.consensus.model;
    requires transitive org.hiero.consensus.pces;
    requires transitive org.hiero.consensus.roster;
    requires transitive org.hiero.consensus.utility;
    requires transitive org.apache.logging.log4j;
    requires com.swirlds.config.extensions;
    requires com.swirlds.logging;
    requires com.swirlds.merkledb;
    requires com.swirlds.state.impl;
    requires com.swirlds.virtualmap;
    requires org.hiero.consensus.hashgraph.impl;
    requires com.fasterxml.jackson.core;
    requires com.fasterxml.jackson.databind;
    requires com.fasterxml.jackson.dataformat.yaml;
    requires com.github.spotbugs.annotations;
    requires com.google.common;
    requires java.annotation;
    requires java.management;
    requires java.scripting;
    requires jdk.management;
    requires jdk.net;
    requires java.desktop;
    requires org.bouncycastle.pkix;
    requires org.bouncycastle.provider;
    requires info.picocli;

    provides com.swirlds.config.api.ConfigurationExtension with
            com.swirlds.platform.config.PlatformConfigurationExtension;
}
