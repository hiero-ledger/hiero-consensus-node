// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.builder;

import static com.swirlds.platform.builder.ConsensusModuleBuilder.CONFIG_PREFIX;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.platform.builder.ConsensusModuleBuilder.NamedProvider;
import com.swirlds.platform.reconnect.ReconnectModule;
import java.util.List;
import java.util.stream.Stream;
import org.hiero.consensus.event.creator.EventCreatorModule;
import org.hiero.consensus.event.intake.EventIntakeModule;
import org.hiero.consensus.gossip.GossipModule;
import org.hiero.consensus.hashgraph.HashgraphModule;
import org.hiero.consensus.pces.PcesModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Unit tests for {@link ConsensusModuleBuilder} config-driven module selection.
 */
class ConsensusModuleBuilderTest {

    private static final Configuration DEFAULT_CONFIG = new TestConfigBuilder().getOrCreateConfig();

    @Test
    @DisplayName("Default config loads EventCreatorModule")
    void defaultEventCreatorModule() {
        assertNotNull(ConsensusModuleBuilder.createEventCreatorModule(DEFAULT_CONFIG));
    }

    @Test
    @DisplayName("Default config loads EventIntakeModule")
    void defaultEventIntakeModule() {
        assertNotNull(ConsensusModuleBuilder.createEventIntakeModule(DEFAULT_CONFIG));
    }

    @Test
    @DisplayName("Default config loads PcesModule")
    void defaultPcesModule() {
        assertNotNull(ConsensusModuleBuilder.createPcesModule(DEFAULT_CONFIG));
    }

    @Test
    @DisplayName("Default config loads HashgraphModule")
    void defaultHashgraphModule() {
        assertNotNull(ConsensusModuleBuilder.createHashgraphModule(DEFAULT_CONFIG));
    }

    @Test
    @DisplayName("Default config loads GossipModule")
    void defaultGossipModule() {
        assertNotNull(ConsensusModuleBuilder.createGossipModule(DEFAULT_CONFIG));
    }

    @Test
    @DisplayName("Default config loads ReconnectModule")
    void defaultReconnectModule() {
        assertNotNull(ConsensusModuleBuilder.createReconnectModule(DEFAULT_CONFIG));
    }

    static Stream<Arguments> explicitModuleArgs() {
        return Stream.of(
                Arguments.of(EventCreatorModule.NAME, "org.hiero.consensus.event.creator.impl"),
                Arguments.of(EventIntakeModule.NAME, "org.hiero.consensus.event.intake.impl"),
                Arguments.of(PcesModule.NAME, "org.hiero.consensus.pces.impl"),
                Arguments.of(HashgraphModule.NAME, "org.hiero.consensus.hashgraph.impl"),
                Arguments.of(GossipModule.NAME, "org.hiero.consensus.gossip.impl"),
                Arguments.of(ReconnectModule.NAME, "org.hiero.consensus.reconnect.impl"));
    }

    @ParameterizedTest(name = "{0} = {1}")
    @MethodSource("explicitModuleArgs")
    @DisplayName("Explicit JPMS module name selects the correct provider")
    void explicitModuleSelection(final String configName, final String jpmsModuleName) {
        final Configuration config = new TestConfigBuilder()
                .withValue(CONFIG_PREFIX + configName, jpmsModuleName)
                .getOrCreateConfig();

        switch (configName) {
            case "eventCreator" -> assertNotNull(ConsensusModuleBuilder.createEventCreatorModule(config));
            case "eventIntake" -> assertNotNull(ConsensusModuleBuilder.createEventIntakeModule(config));
            case "pces" -> assertNotNull(ConsensusModuleBuilder.createPcesModule(config));
            case "hashgraph" -> assertNotNull(ConsensusModuleBuilder.createHashgraphModule(config));
            case "gossip" -> assertNotNull(ConsensusModuleBuilder.createGossipModule(config));
            case "reconnect" -> assertNotNull(ConsensusModuleBuilder.createReconnectModule(config));
            default -> throw new AssertionError("Unknown config name: " + configName);
        }
    }

    static Stream<Arguments> moduleNamesArgs() {
        return Stream.of(
                Arguments.of(EventCreatorModule.NAME),
                Arguments.of(EventIntakeModule.NAME),
                Arguments.of(PcesModule.NAME),
                Arguments.of(HashgraphModule.NAME),
                Arguments.of(GossipModule.NAME),
                Arguments.of(ReconnectModule.NAME));
    }

    @ParameterizedTest(name = "{0} with nonexistent module throws")
    @MethodSource("moduleNamesArgs")
    @DisplayName("Non-existent JPMS module name throws IllegalStateException")
    void nonExistentModuleThrows(final String configName) {
        final Configuration config = new TestConfigBuilder()
                .withValue(CONFIG_PREFIX + configName, "nonexistent.module")
                .getOrCreateConfig();

        switch (configName) {
            case "eventCreator" ->
                assertThrows(
                        IllegalStateException.class, () -> ConsensusModuleBuilder.createEventCreatorModule(config));
            case "eventIntake" ->
                assertThrows(IllegalStateException.class, () -> ConsensusModuleBuilder.createEventIntakeModule(config));
            case "pces" ->
                assertThrows(IllegalStateException.class, () -> ConsensusModuleBuilder.createPcesModule(config));
            case "hashgraph" ->
                assertThrows(IllegalStateException.class, () -> ConsensusModuleBuilder.createHashgraphModule(config));
            case "gossip" ->
                assertThrows(IllegalStateException.class, () -> ConsensusModuleBuilder.createGossipModule(config));
            case "reconnect" ->
                assertThrows(IllegalStateException.class, () -> ConsensusModuleBuilder.createReconnectModule(config));
            default -> throw new AssertionError("Unknown config name: " + configName);
        }
    }

    @Test
    @DisplayName("Empty provider list throws IllegalStateException")
    void emptyProviderListThrows() {
        assertThrows(IllegalStateException.class, () -> ConsensusModuleBuilder.selectModule(List.of(), "test", ""));
    }

    @Test
    @DisplayName("Single provider with empty selection returns that provider")
    void singleProviderDefaultSelection() {
        final String instanceA = "providerA";
        final List<NamedProvider<String>> providers = List.of(new NamedProvider<>("module.a", () -> instanceA));

        final String result = ConsensusModuleBuilder.selectModule(providers, "test", "");

        assertSame(instanceA, result);
    }

    @Test
    @DisplayName("Single provider with matching explicit selection returns that provider")
    void singleProviderExplicitMatch() {
        final String instanceA = "providerA";
        final List<NamedProvider<String>> providers = List.of(new NamedProvider<>("module.a", () -> instanceA));

        final String result = ConsensusModuleBuilder.selectModule(providers, "test", "module.a");

        assertSame(instanceA, result);
    }

    @Test
    @DisplayName("Single provider with non-matching explicit selection throws")
    void singleProviderExplicitMismatch() {
        final List<NamedProvider<String>> providers = List.of(new NamedProvider<>("module.a", () -> "providerA"));

        assertThrows(
                IllegalStateException.class, () -> ConsensusModuleBuilder.selectModule(providers, "test", "module.b"));
    }

    @Test
    @DisplayName("Multiple providers with empty selection throws determinism guard")
    void multipleProvidersNoSelectionThrows() {
        final List<NamedProvider<String>> providers = List.of(
                new NamedProvider<>("module.a", () -> "providerA"), new NamedProvider<>("module.b", () -> "providerB"));

        assertThrows(IllegalStateException.class, () -> ConsensusModuleBuilder.selectModule(providers, "test", ""));
    }

    @Test
    @DisplayName("Multiple providers with explicit selection picks the correct one")
    void multipleProvidersExplicitSelectionPicksCorrect() {
        final String instanceA = "providerA";
        final String instanceB = "providerB";
        final List<NamedProvider<String>> providers = List.of(
                new NamedProvider<>("module.a", () -> instanceA), new NamedProvider<>("module.b", () -> instanceB));

        assertSame(instanceA, ConsensusModuleBuilder.selectModule(providers, "test", "module.a"));
        assertSame(instanceB, ConsensusModuleBuilder.selectModule(providers, "test", "module.b"));
    }

    @Test
    @DisplayName("Multiple providers with non-matching selection throws")
    void multipleProvidersNonMatchingSelectionThrows() {
        final List<NamedProvider<String>> providers = List.of(
                new NamedProvider<>("module.a", () -> "providerA"), new NamedProvider<>("module.b", () -> "providerB"));

        assertThrows(
                IllegalStateException.class, () -> ConsensusModuleBuilder.selectModule(providers, "test", "module.c"));
    }

    @Test
    @DisplayName("Three providers with explicit selection works")
    void threeProvidersExplicitSelection() {
        final String instanceB = "providerB";
        final List<NamedProvider<String>> providers = List.of(
                new NamedProvider<>("module.a", () -> "providerA"),
                new NamedProvider<>("module.b", () -> instanceB),
                new NamedProvider<>("module.c", () -> "providerC"));

        assertSame(instanceB, ConsensusModuleBuilder.selectModule(providers, "test", "module.b"));
    }

    @Test
    @DisplayName("Three providers without selection throws determinism guard")
    void threeProvidersNoSelectionThrows() {
        final List<NamedProvider<String>> providers = List.of(
                new NamedProvider<>("module.c", () -> "providerC"),
                new NamedProvider<>("module.a", () -> "providerA"),
                new NamedProvider<>("module.b", () -> "providerB"));

        assertThrows(IllegalStateException.class, () -> ConsensusModuleBuilder.selectModule(providers, "test", ""));
    }
}
