// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.extensions;

import static com.hedera.services.bdd.spec.HapiPropertySource.getConfigRealm;
import static com.hedera.services.bdd.spec.HapiPropertySource.getConfigShard;

import com.hedera.services.bdd.junit.MultiNetworkHapiTest;
import com.hedera.services.bdd.junit.MultiNetworkHapiTest.Network;
import com.hedera.services.bdd.junit.hedera.HederaNetwork;
import com.hedera.services.bdd.junit.hedera.subprocess.SubProcessNetwork;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;

/**
 * Provisions and injects one or more subprocess networks for {@link MultiNetworkHapiTest}-annotated methods.
 * Networks are injected into {@code HederaNetwork} (or {@code SubProcessNetwork}) parameters in declaration order.
 */
public class MultiNetworkExtension implements BeforeEachCallback, AfterEachCallback, ParameterResolver {
    private static final Logger log = LogManager.getLogger(MultiNetworkExtension.class);
    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(MultiNetworkExtension.class);
    private static final String RESOURCES_KEY = "multiNetworks";
    private static final String PARAM_INDEXES_KEY = "networkParameterIndexes";
    private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(5);

    @Override
    public void beforeEach(@NonNull final ExtensionContext extensionContext) {
        findAnnotation(extensionContext).ifPresent(annotation -> {
            final var networks = startNetworks(annotation.networks());
            store(extensionContext).put(RESOURCES_KEY, networks);
            store(extensionContext)
                    .put(
                            PARAM_INDEXES_KEY,
                            networkParameterIndexes(
                                    extensionContext.getRequiredTestMethod().getParameters().length,
                                    extensionContext.getRequiredTestMethod().getParameters(),
                                    networks.length));
        });
    }

    @Override
    public void afterEach(@NonNull final ExtensionContext extensionContext) {
        final var networks = store(extensionContext).remove(RESOURCES_KEY, SubProcessNetwork[].class);
        if (networks != null) {
            for (final var network : networks) {
                safeTerminate(network);
            }
        }
        store(extensionContext).remove(PARAM_INDEXES_KEY);
    }

    @Override
    public boolean supportsParameter(
            @NonNull final ParameterContext parameterContext, @NonNull final ExtensionContext extensionContext) {
        final var type = parameterContext.getParameter().getType();
        final boolean isSupportedType =
                HederaNetwork.class.isAssignableFrom(type) || SubProcessNetwork.class.isAssignableFrom(type);
        return isSupportedType && findAnnotation(extensionContext).isPresent();
    }

    @Override
    public Object resolveParameter(
            @NonNull final ParameterContext parameterContext, @NonNull final ExtensionContext extensionContext) {
        final var networks = store(extensionContext).get(RESOURCES_KEY, SubProcessNetwork[].class);
        final var indexes = store(extensionContext).get(PARAM_INDEXES_KEY, List.class);
        if (networks == null || indexes == null) {
            throw new IllegalStateException("Multi networks have not been initialized for this test");
        }
        final var index = parameterContext.getIndex();
        final var networkPosition = indexes.indexOf(index);
        if (networkPosition == -1) {
            throw new IllegalArgumentException("Parameter at index " + index + " is not mapped to a network");
        }
        if (networkPosition >= networks.length) {
            throw new IllegalStateException("Unexpected network parameter position: " + networkPosition);
        }
        return networks[networkPosition];
    }

    private SubProcessNetwork[] startNetworks(@NonNull final Network[] networkConfigs) {
        if (networkConfigs.length == 0) {
            throw new IllegalStateException("MultiNetworkHapiTest requires at least one network");
        }
        final Map<String, Integer> nameCounts = Arrays.stream(networkConfigs)
                .map(Network::name)
                .collect(Collectors.toMap(Function.identity(), name -> 1, Integer::sum));
        nameCounts.forEach((name, count) -> {
            if (count > 1) {
                throw new IllegalArgumentException("Network names must be unique, found duplicate: " + name);
            }
        });

        final List<SubProcessNetwork> networks = new ArrayList<>();
        for (final var config : networkConfigs) {
            final var shard = perNetworkShard(config.name());
            final var realm = perNetworkRealm(config.name());
            final var network = SubProcessNetwork.newIsolatedNetwork(config.name(), config.size(), shard, realm);
            // Disable CLPR in multi-network subprocesses to avoid competing CLPR endpoints/ports
            for (long nodeId = 0; nodeId < config.size(); nodeId++) {
                network.getApplicationPropertyOverrides().put(nodeId, List.of("clpr.clprEnabled", "false"));
            }
            networks.add(network);
        }
        try {
            for (final var network : networks) {
                startNetwork(network);
            }
            return networks.toArray(SubProcessNetwork[]::new);
        } catch (Throwable t) {
            log.warn("Failed to start multi-network set, terminating any started subprocess networks", t);
            networks.forEach(this::safeTerminate);
            throw new RuntimeException("Failed to start multi network set", t);
        }
    }

    private void startNetwork(@NonNull final SubProcessNetwork network) {
        network.start();
        network.awaitReady(STARTUP_TIMEOUT);
    }

    private void safeTerminate(final SubProcessNetwork network) {
        if (network == null) {
            return;
        }
        try {
            network.terminate();
        } catch (Throwable t) {
            log.warn("Best-effort cleanup failed for subprocess network {}", network.name(), t);
        }
    }

    private List<Integer> networkParameterIndexes(
            final int parameterCount, final java.lang.reflect.Parameter[] params, final int expectedNetworks) {
        final List<Integer> indexes = new ArrayList<>();
        for (int i = 0; i < parameterCount; i++) {
            final var type = params[i].getType();
            if (HederaNetwork.class.isAssignableFrom(type) || SubProcessNetwork.class.isAssignableFrom(type)) {
                indexes.add(i);
            }
        }
        if (indexes.size() != expectedNetworks) {
            throw new IllegalStateException(
                    "Expected " + expectedNetworks + " HederaNetwork parameters, found " + indexes.size());
        }
        return indexes;
    }

    private long perNetworkShard(@NonNull final String networkName) {
        final var override = System.getProperty("hapi.spec.multinet." + networkName + ".shard");
        return override != null ? Long.parseLong(override) : getConfigShard();
    }

    private long perNetworkRealm(@NonNull final String networkName) {
        final var override = System.getProperty("hapi.spec.multinet." + networkName + ".realm");
        return override != null ? Long.parseLong(override) : getConfigRealm();
    }

    private Optional<MultiNetworkHapiTest> findAnnotation(@NonNull final ExtensionContext extensionContext) {
        final var methodAnnotation = extensionContext
                .getTestMethod()
                .map(method -> method.getAnnotation(MultiNetworkHapiTest.class))
                .orElse(null);
        if (methodAnnotation != null) {
            return Optional.of(methodAnnotation);
        }
        return extensionContext.getTestClass().map(clazz -> clazz.getAnnotation(MultiNetworkHapiTest.class));
    }

    private ExtensionContext.Store store(@NonNull final ExtensionContext extensionContext) {
        return extensionContext.getStore(NAMESPACE);
    }
}
