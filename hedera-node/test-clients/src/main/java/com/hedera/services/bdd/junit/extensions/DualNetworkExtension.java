// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.extensions;

import static com.hedera.services.bdd.junit.extensions.ExtensionUtils.hapiTestMethodOf;
import static com.hedera.services.bdd.spec.HapiPropertySource.getConfigRealm;
import static com.hedera.services.bdd.spec.HapiPropertySource.getConfigShard;

import com.hedera.services.bdd.junit.DualNetworkHapiTest;
import com.hedera.services.bdd.junit.hedera.DualNetwork;
import com.hedera.services.bdd.junit.hedera.subprocess.SubProcessNetwork;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
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
    private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(5);

    @Override
    public void beforeEach(@NonNull final ExtensionContext extensionContext) {
        findAnnotation(extensionContext).ifPresent(annotation -> {
            final var networks = startNetworks(annotation);
            store(extensionContext).put(RESOURCES_KEY, networks);
        });
    }

    @Override
    public void afterEach(@NonNull final ExtensionContext extensionContext) {
        final var networks = store(extensionContext).remove(RESOURCES_KEY, DualNetwork.class);
        if (networks != null) {
            networks.close();
        }
    }

    @Override
    public boolean supportsParameter(
            @NonNull final ParameterContext parameterContext, @NonNull final ExtensionContext extensionContext) {
        return parameterContext.getParameter().getType() == DualNetwork.class
                && findAnnotation(extensionContext).isPresent();
    }

    @Override
    public Object resolveParameter(
            @NonNull final ParameterContext parameterContext, @NonNull final ExtensionContext extensionContext) {
        final var networks = store(extensionContext).get(RESOURCES_KEY, DualNetwork.class);
        if (networks == null) {
            throw new IllegalStateException("Dual networks have not been initialized for this test");
        }
        return networks;
    }

    private DualNetwork startNetworks(@NonNull final DualNetworkHapiTest annotation) {
        SubProcessNetwork primary = null;
        SubProcessNetwork peer = null;
        try {
            primary = SubProcessNetwork.newIsolatedNetwork(
                    annotation.primaryName(), annotation.primarySize(), getConfigShard(), getConfigRealm());
            peer = SubProcessNetwork.newIsolatedNetwork(
                    annotation.peerName(), annotation.peerSize(), getConfigShard(), getConfigRealm());
            startNetwork(primary);
            startNetwork(peer);
            return new DualNetwork(primary, peer);
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
