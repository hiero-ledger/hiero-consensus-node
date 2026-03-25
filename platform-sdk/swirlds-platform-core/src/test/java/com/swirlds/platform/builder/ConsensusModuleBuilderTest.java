// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.builder;

import static com.swirlds.platform.builder.ConsensusModuleBuilderTest.MockHelper.FAKE_PROVIDER_IMPLEMENTATION_A;
import static com.swirlds.platform.builder.ConsensusModuleBuilderTest.MockHelper.FAKE_PROVIDER_IMPLEMENTATION_B;
import static com.swirlds.platform.builder.ConsensusModuleBuilderTest.MockHelper.FAKE_PROVIDER_IMPLEMENTATION_C;
import static com.swirlds.platform.builder.ConsensusModuleBuilderTest.MockHelper.MODULE_B;
import static com.swirlds.platform.builder.ConsensusModuleBuilderTest.MockHelper.fakeProviderWith;
import static com.swirlds.platform.builder.ConsensusModuleBuilderTest.MockHelper.withProviders;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.platform.reconnect.ReconnectModule;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.ServiceLoader;
import java.util.stream.Stream;
import org.hiero.consensus.event.creator.EventCreatorModule;
import org.hiero.consensus.event.intake.EventIntakeModule;
import org.hiero.consensus.gossip.GossipModule;
import org.hiero.consensus.hashgraph.HashgraphModule;
import org.hiero.consensus.pces.PcesModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.MockedStatic;

/**
 * Unit tests for {@link ConsensusModuleBuilder} config-driven module selection.
 */
class ConsensusModuleBuilderTest {

    private static final Configuration DEFAULT_CONFIG = new TestConfigBuilder()
            .withValue("modules.eventIntake", "")
            .withValue("modules.pces", "")
            .withValue("modules.eventCreation", "")
            .withValue("modules.hashgraph", "")
            .withValue("modules.reconnect", "")
            .getOrCreateConfig();
    /** Prefix for all module selection config properties. */
    private static final String MODULE_SUFFIX = "Module";

    /**
     * Single argument source for all module types. Each entry provides:
     * <ol>
     *   <li>{@code moduleClass} — the module interface (e.g. {@code EventCreatorModule.class})</li>
     *   <li>{@code configName} — the derived config property name (e.g. {@code "eventCreator"})</li>
     *   <li>{@code jpmsModuleName} — the real JPMS impl module name (e.g. {@code "org.hiero.consensus.event.creator.impl"})</li>
     * </ol>
     */
    static Stream<Arguments> allModules() {
        return Stream.of(
                Arguments.of(EventCreatorModule.class, "org.hiero.consensus.event.creator.impl"),
                Arguments.of(EventIntakeModule.class, "org.hiero.consensus.event.intake.impl"),
                Arguments.of(PcesModule.class, "org.hiero.consensus.pces.impl"),
                Arguments.of(HashgraphModule.class, "org.hiero.consensus.hashgraph.impl"),
                Arguments.of(GossipModule.class, "org.hiero.consensus.gossip.impl"),
                Arguments.of(ReconnectModule.class, "org.hiero.consensus.reconnect.impl"));
    }

    /**
     * Derive the config property name from the module interface class name.
     * Strips the {@code "Module"} suffix and lowercases the first letter.
     */
    private static String configPropertyName(@NonNull final Class<?> moduleClass) {
        final String simpleName = moduleClass.getSimpleName();
        if (!simpleName.endsWith(MODULE_SUFFIX)) {
            throw new IllegalStateException("Module class name must end with '" + MODULE_SUFFIX + "': " + simpleName);
        }
        final String stripped = simpleName.substring(0, simpleName.length() - MODULE_SUFFIX.length());
        return MODULE_SUFFIX.toLowerCase() + "s." + Character.toLowerCase(stripped.charAt(0)) + stripped.substring(1);
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("allModules")
    @DisplayName("Default config loads module")
    void defaultConfigLoadsModule(final Class<?> moduleClass) {
        assertNotNull(ConsensusModuleBuilder.createModule(moduleClass, DEFAULT_CONFIG));
    }

    @ParameterizedTest(name = "{1} = {2}")
    @MethodSource("allModules")
    @DisplayName("Explicit JPMS module name selects the correct provider")
    void explicitModuleSelection(final Class<?> moduleClass, final String jpmsModuleName) {
        final Configuration config = new TestConfigBuilder()
                .withValue(configPropertyName(moduleClass), jpmsModuleName)
                .getOrCreateConfig();

        assertNotNull(ConsensusModuleBuilder.createModule(moduleClass, config));
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("allModules")
    @DisplayName("Non-existent JPMS module name throws IllegalStateException")
    void nonExistentModuleThrows(final Class<?> moduleClass) {
        final Configuration config = new TestConfigBuilder()
                .withValue(configPropertyName(moduleClass), "nonexistent.module")
                .getOrCreateConfig();

        assertThrows(IllegalStateException.class, () -> ConsensusModuleBuilder.createModule(moduleClass, config));
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("allModules")
    @DisplayName("Multiple providers with empty selection throws determinism guard")
    void multipleProvidersNoSelectionThrows(final Class<?> moduleClass) {
        withProviders(
                List.of(
                        fakeProviderWith(mock(moduleClass), FAKE_PROVIDER_IMPLEMENTATION_A),
                        fakeProviderWith(mock(moduleClass), FAKE_PROVIDER_IMPLEMENTATION_B)),
                () -> assertThrows(
                        IllegalStateException.class,
                        () -> ConsensusModuleBuilder.createModule(moduleClass, DEFAULT_CONFIG)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allModules")
    @DisplayName("Multiple providers with explicit selection picks the correct one")
    void multipleProvidersExplicitSelectionPicksCorrect(final Class<?> moduleClass) {
        final Object facade = mock(moduleClass);
        final Configuration config = new TestConfigBuilder()
                .withValue(configPropertyName(moduleClass), MODULE_B)
                .getOrCreateConfig();

        withProviders(
                List.of(
                        fakeProviderWith(mock(moduleClass), FAKE_PROVIDER_IMPLEMENTATION_A),
                        fakeProviderWith(facade, FAKE_PROVIDER_IMPLEMENTATION_B)),
                () -> assertSame(facade, ConsensusModuleBuilder.createModule(moduleClass, config)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allModules")
    @DisplayName("Multiple providers with non-matching selection throws")
    void multipleProvidersNonMatchingSelectionThrows(final Class<?> moduleClass) {
        final Configuration config = new TestConfigBuilder()
                .withValue(configPropertyName(moduleClass), "nonexistent.module")
                .getOrCreateConfig();

        withProviders(
                List.of(
                        fakeProviderWith(mock(moduleClass), FAKE_PROVIDER_IMPLEMENTATION_A),
                        fakeProviderWith(mock(moduleClass), FAKE_PROVIDER_IMPLEMENTATION_B)),
                () -> assertThrows(
                        IllegalStateException.class, () -> ConsensusModuleBuilder.createModule(moduleClass, config)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allModules")
    @DisplayName("Three providers with explicit selection works")
    void threeProvidersExplicitSelection(final Class<?> moduleClass) {
        final Object facade = mock(moduleClass);
        final Configuration config = new TestConfigBuilder()
                .withValue(configPropertyName(moduleClass), MODULE_B)
                .getOrCreateConfig();

        withProviders(
                List.of(
                        fakeProviderWith(mock(moduleClass), FAKE_PROVIDER_IMPLEMENTATION_A),
                        fakeProviderWith(facade, FAKE_PROVIDER_IMPLEMENTATION_B),
                        fakeProviderWith(mock(moduleClass), FAKE_PROVIDER_IMPLEMENTATION_C)),
                () -> assertSame(facade, ConsensusModuleBuilder.createModule(moduleClass, config)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allModules")
    @DisplayName("Three providers without selection throws determinism guard")
    void threeProvidersNoSelectionThrows(final Class<?> moduleClass) {
        withProviders(
                List.of(
                        fakeProviderWith(mock(moduleClass), FAKE_PROVIDER_IMPLEMENTATION_C),
                        fakeProviderWith(mock(moduleClass), FAKE_PROVIDER_IMPLEMENTATION_A),
                        fakeProviderWith(mock(moduleClass), FAKE_PROVIDER_IMPLEMENTATION_B)),
                () -> assertThrows(
                        IllegalStateException.class,
                        () -> ConsensusModuleBuilder.createModule(moduleClass, DEFAULT_CONFIG)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allModules")
    @DisplayName("Single provider with empty selection returns that provider")
    void singleMockedProviderDefaultSelection(final Class<?> moduleClass) {
        final Object impl = mock(moduleClass);

        withProviders(
                List.of(fakeProviderWith(impl, FAKE_PROVIDER_IMPLEMENTATION_A)),
                () -> assertSame(impl, ConsensusModuleBuilder.createModule(moduleClass, DEFAULT_CONFIG)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allModules")
    @DisplayName("No providers throws IllegalStateException")
    void noProvidersThrows(final Class<?> moduleClass) {
        withProviders(
                List.of(),
                () -> assertThrows(
                        IllegalStateException.class,
                        () -> ConsensusModuleBuilder.createModule(moduleClass, DEFAULT_CONFIG)));
    }

    /**
     * Mocking infrastructure for synthetic multi-provider tests.
     *
     * <p>We mock {@code ConsensusModuleBuilder.loadProviders()} (not {@code ServiceLoader}
     * itself) to avoid interfering with JDK internals that also use
     * {@code ServiceLoader}. Mockito cannot mock {@code Class.class}, so fake providers use
     * real JDK classes from distinct named modules as {@code Provider.type()} return values.
     */
    static final class MockHelper {

        // Real JDK classes from distinct named modules used as Provider.type() identifiers
        static final Class<?> FAKE_PROVIDER_IMPLEMENTATION_A = String.class; // java.base
        static final Class<?> FAKE_PROVIDER_IMPLEMENTATION_B = javax.management.MBeanServer.class; // java.management
        static final Class<?> FAKE_PROVIDER_IMPLEMENTATION_C = javax.script.ScriptEngine.class; // java.scripting
        static final String MODULE_B =
                FAKE_PROVIDER_IMPLEMENTATION_B.getModule().getName();

        private MockHelper() {}

        /**
         * Mock {@code ConsensusModuleBuilder.loadProviders()} to return the given providers,
         * then run the assertion block inside the mock scope.
         */
        @SuppressWarnings("unchecked")
        static void withProviders(final List<ServiceLoader.Provider<?>> providers, final Runnable assertion) {
            try (MockedStatic<ConsensusModuleBuilder> cmb =
                    mockStatic(ConsensusModuleBuilder.class, CALLS_REAL_METHODS)) {
                cmb.when(() -> ConsensusModuleBuilder.loadProviders(any(Class.class)))
                        .thenReturn(providers);
                assertion.run();
            }
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        static <T> ServiceLoader.Provider<T> fakeProviderWith(
                final T providerInstance, final Class<?> fakeProviderImplementationClass) {
            final ServiceLoader.Provider provider = mock(ServiceLoader.Provider.class);
            when(provider.get()).thenReturn(providerInstance);
            when(provider.type()).thenReturn(fakeProviderImplementationClass);
            return provider;
        }
    }
}
