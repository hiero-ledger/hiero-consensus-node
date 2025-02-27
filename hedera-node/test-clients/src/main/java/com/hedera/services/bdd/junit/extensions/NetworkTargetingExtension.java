// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.extensions;

import static com.hedera.services.bdd.junit.ContextRequirement.FEE_SCHEDULE_OVERRIDES;
import static com.hedera.services.bdd.junit.ContextRequirement.THROTTLE_OVERRIDES;
import static com.hedera.services.bdd.junit.extensions.ExtensionUtils.hapiTestMethodOf;
import static com.hedera.services.bdd.junit.hedera.embedded.EmbeddedMode.CONCURRENT;
import static com.hedera.services.bdd.junit.hedera.embedded.EmbeddedMode.REPEATABLE;
import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.workingDirVersion;
import static com.hedera.services.bdd.spec.HapiSpec.doTargetSpec;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toMap;
import static org.junit.platform.commons.support.AnnotationSupport.isAnnotated;

import com.hedera.hapi.util.HapiUtils;
import com.hedera.node.app.fixtures.state.FakeState;
import com.hedera.services.bdd.junit.ConfigOverride;
import com.hedera.services.bdd.junit.ContextRequirement;
import com.hedera.services.bdd.junit.GenesisHapiTest;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.LeakyEmbeddedHapiTest;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.LeakyRepeatableHapiTest;
import com.hedera.services.bdd.junit.SharedNetworkLauncherSessionListener;
import com.hedera.services.bdd.junit.TargetEmbeddedMode;
import com.hedera.services.bdd.junit.hedera.HederaNetwork;
import com.hedera.services.bdd.junit.hedera.embedded.EmbeddedMode;
import com.hedera.services.bdd.junit.hedera.embedded.EmbeddedNetwork;
import com.hedera.services.bdd.junit.restart.RestartHapiTest;
import com.hedera.services.bdd.junit.restart.SavedStateSpec;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.keys.RepeatableKeyGenerator;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * An extension that binds the target network to the current thread before invoking
 * each {@link HapiTest}-annotated test method.
 *
 * <p><b>(FUTURE)</b> - implement {@link org.junit.jupiter.api.extension.BeforeAllCallback}
 * and {@link org.junit.jupiter.api.extension.AfterAllCallback} to handle creating "private"
 * networks for annotated test classes and targeting them instead of the shared network.
 */
public class NetworkTargetingExtension implements BeforeEachCallback, AfterEachCallback {
    private static final String SPEC_NAME = "<RESTART>";

    public static final AtomicReference<HederaNetwork> SHARED_NETWORK = new AtomicReference<>();
    public static final AtomicReference<RepeatableKeyGenerator> REPEATABLE_KEY_GENERATOR = new AtomicReference<>();

    @Override
    public void beforeEach(@NonNull final ExtensionContext extensionContext) {
        hapiTestMethodOf(extensionContext).ifPresent(method -> {
            if (isAnnotated(method, GenesisHapiTest.class)) {
                final var targetNetwork =
                        new EmbeddedNetwork(method.getName().toUpperCase(), method.getName(), CONCURRENT);
                final var a = method.getAnnotation(GenesisHapiTest.class);
                final var bootstrapOverrides = Arrays.stream(a.bootstrapOverrides())
                        .collect(toMap(ConfigOverride::key, ConfigOverride::value));
                targetNetwork.startWith(bootstrapOverrides);
                HapiSpec.TARGET_NETWORK.set(targetNetwork);
            } else if (isAnnotated(method, RestartHapiTest.class)) {
                final var targetNetwork =
                        new EmbeddedNetwork(method.getName().toUpperCase(), method.getName(), REPEATABLE);
                final var a = method.getAnnotation(RestartHapiTest.class);

                final var setupOverrides =
                        Arrays.stream(a.setupOverrides()).collect(toMap(ConfigOverride::key, ConfigOverride::value));

                final var restartOverrides =
                        Arrays.stream(a.restartOverrides()).collect(toMap(ConfigOverride::key, ConfigOverride::value));

                switch (a.restartType()) {
                    case GENESIS -> targetNetwork.startWith(restartOverrides);
                    case SAME_VERSION -> targetNetwork.startWith(setupOverrides);
                    case UPGRADE_BOUNDARY -> startFromPreviousVersion(targetNetwork, setupOverrides);
                }
                switch (a.restartType()) {
                    case GENESIS -> {
                        // The restart was from genesis, so nothing else to do
                    }
                    case SAME_VERSION, UPGRADE_BOUNDARY -> {
                        final var state = postGenesisStateOf(targetNetwork, a);
                        targetNetwork.restart(state, restartOverrides);
                    }
                }
                HapiSpec.TARGET_NETWORK.set(targetNetwork);
            } else {
                ensureEmbeddedNetwork(extensionContext);
                HapiSpec.TARGET_NETWORK.set(SHARED_NETWORK.get());
                // If there are properties to preserve or system files to override and restore, bind that info to the
                // thread before executing the test factory
                if (isAnnotated(method, LeakyHapiTest.class)) {
                    final var a = method.getAnnotation(LeakyHapiTest.class);
                    bindThreadTargets(a.requirement(), a.overrides(), a.throttles(), a.fees());
                } else if (isAnnotated(method, LeakyEmbeddedHapiTest.class)) {
                    final var a = method.getAnnotation(LeakyEmbeddedHapiTest.class);
                    bindThreadTargets(a.requirement(), a.overrides(), a.throttles(), a.fees());
                } else if (isAnnotated(method, LeakyRepeatableHapiTest.class)) {
                    final var a = method.getAnnotation(LeakyRepeatableHapiTest.class);
                    bindThreadTargets(new ContextRequirement[] {}, a.overrides(), a.throttles(), a.fees());
                }
            }
        });
    }

    @Override
    public void afterEach(@NonNull final ExtensionContext extensionContext) {
        HapiSpec.TARGET_NETWORK.remove();
        HapiSpec.FEES_OVERRIDE.remove();
        HapiSpec.THROTTLES_OVERRIDE.remove();
        HapiSpec.PROPERTIES_TO_PRESERVE.remove();
    }

    /**
     * Ensures that the embedded network is running, if required by the test class or method.
     *
     * @param extensionContext the extension context
     */
    public static void ensureEmbeddedNetwork(@NonNull final ExtensionContext extensionContext) {
        requireNonNull(extensionContext);
        requiredEmbeddedMode(extensionContext)
                .ifPresent(SharedNetworkLauncherSessionListener.SharedNetworkExecutionListener::ensureEmbedding);
    }

    /**
     * Returns the embedded mode required by the test class or method, if any.
     *
     * @param extensionContext the extension context
     * @return the embedded mode
     */
    private static Optional<EmbeddedMode> requiredEmbeddedMode(@NonNull final ExtensionContext extensionContext) {
        requireNonNull(extensionContext);
        return extensionContext
                .getTestClass()
                .map(type -> type.getAnnotation(TargetEmbeddedMode.class))
                .map(TargetEmbeddedMode::value)
                .or(() -> extensionContext.getParent().flatMap(NetworkTargetingExtension::requiredEmbeddedMode));
    }

    private void bindThreadTargets(
            @NonNull final ContextRequirement[] requirement,
            @NonNull final String[] overrides,
            @NonNull final String throttles,
            @NonNull final String fees) {
        HapiSpec.PROPERTIES_TO_PRESERVE.set(List.of(overrides));
        HapiSpec.THROTTLES_OVERRIDE.set(effectiveResource(requirement, THROTTLE_OVERRIDES, throttles));
        HapiSpec.FEES_OVERRIDE.set(effectiveResource(requirement, FEE_SCHEDULE_OVERRIDES, fees));
    }

    /**
     * If there is an explicit resource to load, returns it; otherwise returns null if the test's
     * context requirement does not include the relevant requirement.
     *
     * @param contextRequirements the context requirements of the test
     * @param relevantRequirement the relevant context requirement
     * @param resource the path to the resource
     * @return the effective resource
     */
    private @Nullable String effectiveResource(
            @NonNull final ContextRequirement[] contextRequirements,
            @NonNull final ContextRequirement relevantRequirement,
            @NonNull final String resource) {
        if (!resource.isBlank()) {
            return resource;
        }
        return List.of(contextRequirements).contains(relevantRequirement) ? "" : null;
    }

    /**
     * Starts the given target embedded network from the previous version with any other requested overrides.
     *
     * @param targetNetwork the target network
     * @param overrides the overrides
     */
    private void startFromPreviousVersion(
            @NonNull final EmbeddedNetwork targetNetwork, @NonNull final Map<String, String> overrides) {
        final Map<String, String> netOverrides = new HashMap<>(overrides);
        final var currentVersion = workingDirVersion();
        final var previousVersion = currentVersion
                .copyBuilder()
                .minor(currentVersion.minor() - 1)
                .patch(0)
                .pre("")
                .build("")
                .build();
        netOverrides.put("hedera.services.version", HapiUtils.toString(previousVersion));
        targetNetwork.startWith(netOverrides);
    }

    private FakeState postGenesisStateOf(
            @NonNull final EmbeddedNetwork targetNetwork, @NonNull final RestartHapiTest a) {
        final var spec = new HapiSpec(SPEC_NAME, new SpecOperation[] {cryptoCreate("genesisAccount")});
        doTargetSpec(spec, targetNetwork);
        try {
            spec.execute();
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
        final SavedStateSpec savedStateSpec;
        try {
            savedStateSpec = a.savedStateSpec().getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        final var state = targetNetwork.embeddedHederaOrThrow().state();
        savedStateSpec.accept(state);
        return state;
    }
}
