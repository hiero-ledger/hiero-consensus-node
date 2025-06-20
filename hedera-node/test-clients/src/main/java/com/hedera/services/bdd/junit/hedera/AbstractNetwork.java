// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.hedera;

import static com.hedera.services.bdd.spec.HapiPropertySource.inPriorityOrder;
import static java.util.Objects.requireNonNull;

import com.hedera.node.app.config.ConfigProviderImpl;
import com.hedera.node.app.config.ServicesConfigExtension;
import com.hedera.services.bdd.junit.hedera.subprocess.ProcessUtils;
import com.hedera.services.bdd.spec.HapiPropertySource;
import com.hedera.services.bdd.spec.props.MapPropertySource;
import com.hedera.services.bdd.spec.props.MapPropertySource.Quiet;
import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Implementation support for named network with a list of nodes (which may all actually be the same object
 * in the case of an embedded "network"); along with the known configuration overrides, if any, for the network.
 */
public abstract class AbstractNetwork implements HederaNetwork {
    private final HapiPropertySource startupProperties;
    protected final String networkName;
    protected final List<HederaNode> nodes;

    protected AbstractNetwork(@NonNull final String networkName, @NonNull final List<HederaNode> nodes) {
        this.networkName = requireNonNull(networkName);
        this.nodes = new ArrayList<>(requireNonNull(nodes));
        this.startupProperties = inPriorityOrder(
                Stream.of(ciCheckOverrides(), networkOverrides(), environmentDefaults(), servicesDefaults())
                        .filter(Objects::nonNull)
                        .toArray(HapiPropertySource[]::new));
    }

    @Override
    public List<HederaNode> nodes() {
        return nodes;
    }

    @Override
    public String name() {
        return networkName;
    }

    @Override
    public @NonNull HapiPropertySource startupProperties() {
        return startupProperties;
    }

    /**
     * Returns a non-null property source with any known overrides to the network default configuration.
     *
     * @return the known overrides, or null if there are none
     */
    protected @Nullable HapiPropertySource networkOverrides() {
        return null;
    }

    /**
     * Returns a property source with any PR check overrides, if some exist.
     */
    private HapiPropertySource ciCheckOverrides() {
        final var maybeOverrides = ProcessUtils.prCheckOverrides();
        return maybeOverrides.isEmpty() ? null : new MapPropertySource(maybeOverrides);
    }

    private HapiPropertySource environmentDefaults() {
        final var defaultConfig = new ConfigProviderImpl(true).getConfiguration();
        return new HapiPropertySource() {
            @Override
            public String get(@NonNull final String property) {
                return defaultConfig.getValue(property);
            }

            @Override
            public boolean has(@NonNull final String property) {
                return defaultConfig.exists(property);
            }
        };
    }

    private HapiPropertySource servicesDefaults() {
        return new MapPropertySource(allDefaultsFrom(new ServicesConfigExtension().getConfigDataTypes()), Quiet.YES);
    }

    private static Map<String, String> allDefaultsFrom(@NonNull final Set<Class<? extends Record>> configTypes) {
        return Map.ofEntries(configTypes.stream()
                .flatMap(AbstractNetwork::defaultsFrom)
                .<Map.Entry<String, String>>toArray(Map.Entry[]::new));
    }

    private static Stream<Map.Entry<String, String>> defaultsFrom(@NonNull final Class<? extends Record> configType) {
        final var prefix = prefixFor(configType);
        return Arrays.stream(configType.getRecordComponents())
                .map(component -> Map.entry(fullName(prefix, component), requiredDefaultValue(component)));
    }

    private static String requiredDefaultValue(@NonNull final RecordComponent component) {
        return Optional.ofNullable(component.getAnnotation(ConfigProperty.class))
                .map(ConfigProperty::defaultValue)
                .orElseThrow();
    }

    private static <T extends Record> String prefixFor(@NonNull final Class<T> type) {
        return Optional.ofNullable(type.getAnnotation(ConfigData.class))
                .map(ConfigData::value)
                .orElse("");
    }

    private static String fullName(@NonNull final String prefix, @NonNull final RecordComponent component) {
        return Optional.ofNullable(component.getAnnotation(ConfigProperty.class))
                .map(annotation -> {
                    if (!annotation.value().isBlank()) {
                        return fullName(prefix, annotation.value());
                    } else {
                        return fullName(prefix, component.getName());
                    }
                })
                .orElseGet(() -> fullName(prefix, component.getName()));
    }

    private static String fullName(@NonNull final String prefix, @NonNull final String name) {
        if (prefix.isBlank()) {
            return name;
        }
        return prefix + "." + name;
    }
}
