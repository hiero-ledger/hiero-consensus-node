// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.builder;

import static com.swirlds.logging.legacy.LogMarker.ERROR;
import static com.swirlds.logging.legacy.LogMarker.STARTUP;

import com.swirlds.config.api.Configuration;
import com.swirlds.platform.reconnect.ReconnectModule;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.ServiceLoader;
import java.util.ServiceLoader.Provider;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.event.creator.EventCreatorModule;
import org.hiero.consensus.event.intake.EventIntakeModule;
import org.hiero.consensus.gossip.GossipModule;
import org.hiero.consensus.hashgraph.HashgraphModule;
import org.hiero.consensus.pces.PcesModule;

/**
 * A builder for consensus modules using the ServiceLoader mechanism.
 *
 * <p>Module selection is driven by {@link ModulesConfig}. When a config property is set, the
 * provider whose JPMS module name matches the property value is selected. When the property is
 * empty and only one provider exists, that provider is used. When the property is empty but
 * multiple providers exist, an {@link IllegalStateException} is thrown to prevent
 * non-deterministic selection across nodes.
 */
public class ConsensusModuleBuilder {

    private static final Logger log = LogManager.getLogger(ConsensusModuleBuilder.class);

    private ConsensusModuleBuilder() {}

    /**
     * Create a module implementation via {@link ServiceLoader}, selecting by JPMS module name when configured, and
     * enforcing determinism when multiple providers are available.
     *
     * <p>The config property name is derived from the interface's simple name by stripping the
     * {@code "Module"} suffix and lowercasing the first letter (e.g. {@code EventCreatorModule}
     * becomes config key {@code "modules.eventCreator"}).
     *
     * @param <T>            the module interface type
     * @param moduleClass    the module interface class (must end with {@code "Module"})
     * @param configuration  the configuration to read the selected module from
     * @return the selected module instance
     * @throws IllegalStateException if no provider is found, multiple providers exist without explicit selection,
     *                               or the class name does not follow the naming convention
     */
    public static <T> T createModule(@NonNull final Class<T> moduleClass, @NonNull final Configuration configuration) {
        try {

            final List<Provider<T>> providers = loadProviders(moduleClass);

            if (providers.isEmpty()) {
                throw new IllegalStateException("No " + moduleClass.getSimpleName() + " implementation found!");
            }
            final String selectedModule = getSelectedModule(moduleClass, configuration);
            final Provider<T> provider;
            if ("".equals(selectedModule)) {
                if (providers.size() > 1) {
                    final String available = providers.stream()
                            .map(ConsensusModuleBuilder::providerModuleName)
                            .collect(Collectors.joining(", "));
                    throw new IllegalStateException("Multiple " + moduleClass.getSimpleName() + " providers found ["
                            + available + "] but no explicit selection has been configured. "
                            + "Explicit selection is required to guarantee determinism.");
                }
                provider = providers.getFirst();
            } else {
                provider = providers.stream()
                        .filter(p -> providerModuleName(p).equals(selectedModule))
                        .findFirst()
                        .orElseThrow(() -> {
                            final String available = providers.stream()
                                    .map(ConsensusModuleBuilder::providerModuleName)
                                    .sorted()
                                    .collect(Collectors.joining(", "));
                            return new IllegalStateException(
                                    "No " + moduleClass.getSimpleName() + " provider found in requested module '"
                                            + selectedModule + "'. Available: [" + available + "]");
                        });
            }
            final T module = provider.get();

            log.info(
                    STARTUP.getMarker(),
                    "Loaded {} module: {} (from {})",
                    moduleClass.getSimpleName(),
                    module.getClass().getSimpleName(),
                    providerModuleName(provider));
            return module;
        } catch (final IllegalStateException e) {
            log.error(ERROR.getMarker(), e.getMessage(), e);

            throw e;
        }
    }

    private static <T> String getSelectedModule(
            @NonNull final Class<T> moduleClass, @NonNull final Configuration configuration) {
        final ModulesConfig modulesConfig = configuration.getConfigData(ModulesConfig.class);

        if (moduleClass.equals(EventCreatorModule.class)) return modulesConfig.eventCreator();
        if (moduleClass.equals(EventIntakeModule.class)) return modulesConfig.eventIntake();
        if (moduleClass.equals(GossipModule.class)) return modulesConfig.gossip();
        if (moduleClass.equals(HashgraphModule.class)) return modulesConfig.hashgraph();
        if (moduleClass.equals(ReconnectModule.class)) return modulesConfig.reconnect();
        if (moduleClass.equals(PcesModule.class)) return modulesConfig.pces();

        throw new IllegalStateException("Module:" + moduleClass.getSimpleName() + " not recognized");
    }

    /**
     * Load all {@link ServiceLoader} providers for the given module interface.
     */
    static <T> List<Provider<T>> loadProviders(@NonNull final Class<T> moduleClass) {
        return ServiceLoader.load(moduleClass).stream().toList();
    }

    /**
     * Get a stable identifier for a ServiceLoader provider. Uses the JPMS module name when
     * available; falls back to the provider class's package name for classpath (unnamed module)
     * environments such as containers.
     */
    private static <T> String providerModuleName(@NonNull final Provider<T> provider) {
        final String jpmsName = provider.type().getModule().getName();
        return jpmsName != null ? jpmsName : provider.type().getPackageName();
    }
}
