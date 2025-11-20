// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.extensions;

import static com.hedera.services.bdd.junit.extensions.ExtensionUtils.hapiTestMethodOf;
import static com.hedera.services.bdd.spec.HapiPropertySource.getConfigRealm;
import static com.hedera.services.bdd.spec.HapiPropertySource.getConfigShard;

import com.hedera.services.bdd.junit.DualNetworkHapiTest;
import com.hedera.services.bdd.junit.hedera.HederaNetwork;
import com.hedera.services.bdd.junit.hedera.NetworkRole;
import com.hedera.services.bdd.junit.hedera.subprocess.SubProcessNetwork;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;

/**
 * Provisions and injects dual subprocess networks for {@link DualNetworkHapiTest}-annotated methods.
 */
public class DualNetworkExtension implements BeforeEachCallback, AfterEachCallback, ParameterResolver {
    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(DualNetworkExtension.class);
    private static final String RESOURCES_KEY = "dualNetworks";
    private static final String PARAM_INDEXES_KEY = "networkParameterIndexes";
    private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(5);

    @Override
    public void beforeEach(@NonNull final ExtensionContext extensionContext) {
        findAnnotation(extensionContext).ifPresent(annotation -> {
            final var networks = startNetworks(annotation);
            store(extensionContext).put(RESOURCES_KEY, networks);
            store(extensionContext)
                    .put(
                            PARAM_INDEXES_KEY,
                            networkParameterIndexes(
                                    extensionContext.getRequiredTestMethod().getParameters().length,
                                    extensionContext.getRequiredTestMethod().getParameters()));
        });
    }

    @Override
    public void afterEach(@NonNull final ExtensionContext extensionContext) {
        final var networks = store(extensionContext).remove(RESOURCES_KEY, SubProcessNetwork[].class);
        if (networks != null) {
            safeTerminate(networks[0]);
            safeTerminate(networks[1]);
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
            throw new IllegalStateException("Dual networks have not been initialized for this test");
        }
        final var index = parameterContext.getIndex();
        if (indexes.isEmpty()) {
            throw new IllegalStateException("No network parameters were detected for this dual-network test");
        }
        final var networkPosition = indexes.indexOf(index);
        if (networkPosition == -1) {
            throw new IllegalArgumentException("Parameter at index " + index + " is not mapped to a dual network");
        }
        if (networkPosition == 0) {
            return networks[0];
        } else if (networkPosition == 1) {
            return networks[1];
        } else {
            throw new IllegalStateException("Unexpected network parameter position: " + networkPosition);
        }
    }

    private SubProcessNetwork[] startNetworks(@NonNull final DualNetworkHapiTest annotation) {
        SubProcessNetwork primary = null;
        SubProcessNetwork peer = null;
        try {
            primary = SubProcessNetwork.newIsolatedNetwork(
                    annotation.primaryName(), annotation.primarySize(), getConfigShard(), getConfigRealm());
            peer = SubProcessNetwork.newIsolatedNetwork(
                    annotation.peerName(), annotation.peerSize(), getConfigShard(), getConfigRealm());
            primary.setRole(NetworkRole.PRIMARY);
            peer.setRole(NetworkRole.PEER);
            primary.setPeer(peer);
            peer.setPeer(primary);
            startNetwork(primary);
            startNetwork(peer);
            return new SubProcessNetwork[] {primary, peer};
        } catch (Throwable t) {
            safeTerminate(peer);
            safeTerminate(primary);
            throw new RuntimeException("Failed to start dual networks", t);
        }
    }

    private void startNetwork(@NonNull final SubProcessNetwork network) {
        network.start();
        network.awaitReady(STARTUP_TIMEOUT);
    }

    private void safeTerminate(final SubProcessNetwork network) {
        if (network != null) {
            try {
                network.terminate();
            } catch (Throwable ignore) {
                // best-effort cleanup
            }
        }
    }

    private List<Integer> networkParameterIndexes(final int parameterCount, final java.lang.reflect.Parameter[] params) {
        final List<Integer> indexes = new ArrayList<>();
        for (int i = 0; i < parameterCount; i++) {
            final var type = params[i].getType();
            if (HederaNetwork.class.isAssignableFrom(type) || SubProcessNetwork.class.isAssignableFrom(type)) {
                indexes.add(i);
            }
        }
        if (indexes.size() != 2) {
            throw new IllegalStateException("Dual-network tests require two HederaNetwork parameters (primary, peer)");
        }
        return indexes;
    }

    private Optional<DualNetworkHapiTest> findAnnotation(@NonNull final ExtensionContext extensionContext) {
        final var methodAnnotation = hapiTestMethodOf(extensionContext)
                .map(method -> method.getAnnotation(DualNetworkHapiTest.class))
                .orElse(null);
        if (methodAnnotation != null) {
            return Optional.of(methodAnnotation);
        }
        return extensionContext.getTestClass().map(clazz -> clazz.getAnnotation(DualNetworkHapiTest.class));
    }

    private ExtensionContext.Store store(@NonNull final ExtensionContext extensionContext) {
        return extensionContext.getStore(NAMESPACE);
    }
}
