// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.builder;

import static com.swirlds.logging.legacy.LogMarker.ERROR;
import static com.swirlds.logging.legacy.LogMarker.STARTUP;

import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.ServiceLoader;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

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

    /** Prefix for all module selection config properties. */
    private static final String CONFIG_PREFIX = "modules.";

    /** Suffix that is stripped from the interface name to derive the config property name. */
    private static final String MODULE_SUFFIX = "Module";

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

            final String moduleName = deriveModuleName(moduleClass);
            final String configKey = CONFIG_PREFIX + moduleName;
            final String selectedModule = configuration.getValue(configKey, String.class, "");

            final List<ServiceLoader.Provider<T>> providers = loadProviders(moduleClass);

            if (providers.isEmpty()) {
                throw new IllegalStateException("No " + moduleName + " implementation found!");
            }
            final ServiceLoader.Provider<T> provider;
            if ("".equals(selectedModule)) {
                if (providers.size() > 1) {
                    final String available = providers.stream()
                            .map(ConsensusModuleBuilder::providerModuleName)
                            .collect(Collectors.joining(", "));
                    throw new IllegalStateException("Multiple " + moduleName + " providers found ["
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
                            return new IllegalStateException("No " + moduleName + " found in module '" + selectedModule
                                    + "'. Available: [" + available + "]");
                        });
            }
            final T module = provider.get();

            log.info(
                    STARTUP.getMarker(),
                    "Loaded {} module: {} (from {})",
                    moduleName,
                    module.getClass().getSimpleName(),
                    providerModuleName(provider));
            return module;
        } catch (final IllegalStateException e) {
            log.error(ERROR.getMarker(), e.getMessage(), e);

            throw e;
        }
    }

    /**
     * Derive the config property name from the module interface class name.
     * Strips the {@code "Module"} suffix and lowercases the first letter.
     */
    static String deriveModuleName(@NonNull final Class<?> moduleClass) {
        final String simpleName = moduleClass.getSimpleName();
        if (!simpleName.endsWith(MODULE_SUFFIX)) {
            throw new IllegalStateException("Module class name must end with '" + MODULE_SUFFIX + "': " + simpleName);
        }
        final String stripped = simpleName.substring(0, simpleName.length() - MODULE_SUFFIX.length());
        return Character.toLowerCase(stripped.charAt(0)) + stripped.substring(1);
    }

    /**
     * Load all {@link ServiceLoader} providers for the given module interface.
     */
    static <T> List<ServiceLoader.Provider<T>> loadProviders(@NonNull final Class<T> moduleClass) {
        return ServiceLoader.load(moduleClass).stream().toList();
    }

    /**
     * Get a stable identifier for a ServiceLoader provider. Uses the JPMS module name when
     * available; falls back to the provider class's package name for classpath (unnamed module)
     * environments such as containers.
     */
    private static <T> String providerModuleName(@NonNull final ServiceLoader.Provider<T> provider) {
        final String jpmsName = provider.type().getModule().getName();
        return jpmsName != null ? jpmsName : provider.type().getPackageName();
    }
}
