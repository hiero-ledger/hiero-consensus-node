// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit;

import com.hedera.services.bdd.junit.extensions.MultiNetworkExtension;
import com.hedera.services.bdd.junit.hedera.ExternalPath;
import com.hedera.services.bdd.junit.hedera.subprocess.SubProcessNetwork;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.config.Configurator;
import org.junit.platform.commons.support.AnnotationSupport;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestPlan;

/**
 * JUnit Platform launcher-session listener that runs once per test plan, before any tests execute.
 * Scans the plan for {@link MultiNetworkHapiTest} annotations on both classes and methods, starts
 * one {@link SubProcessNetwork} per distinct network name (e.g. {@code ledgerA}, {@code ledgerB})
 * via {@link MultiNetworkExtension#startNetworks}, and stashes each in
 * {@link MultiNetworkExtension#SHARED_NETWORKS} so every annotated test reuses the same subprocess
 * network by default. Terminates all networks after the plan finishes.
 */
public class SharedMultiNetworkLauncherSessionListener implements LauncherSessionListener {
    private static final Logger log = LogManager.getLogger(SharedMultiNetworkLauncherSessionListener.class);

    /**
     * System property keys consumed by {@code log4j2-test-client.xml}'s {@code RollingFile} appender.
     * Mirrors the constants in {@code SharedNetworkLauncherSessionListener}; duplicated locally to
     * keep this listener self-contained.
     */
    private static final String TEST_CLIENT_LOG_FILE = "hapi.test.clients.log.file";

    private static final String TEST_CLIENT_LOG_FILE_PATTERN = "hapi.test.clients.log.filePattern";

    /**
     * Sibling directory (of the per-network {@code <scope>-test/} dirs) where the multi-network
     * test-client driver log lives.
     */
    private static final String MULTINETWORK_LOG_DIR = "multinetwork-test-clients";

    /**
     * A list of {@code @MultiNetworkHapiTest} annotation declarations, grouped by network name.
     */
    private final Map<String, List<SharedMultiNetworkExecutionListener.NetworkDeclaration>> declarationsByName =
            new LinkedHashMap<>();

    /**
     * Method keys already passed through {@link SharedMultiNetworkExecutionListener#collectDeclarations}.
     *
     * <p>A test method typically surfaces twice in the plan walk - once via its enclosing
     * container's {@link ClassSource}, and once via its own {@link MethodSource}.
     * Deduping here prevents double-appending the same {@code @Network} declarations to {@link #declarationsByName}.
     */
    private final Set<String> seenMethods = new HashSet<>();

    @Override
    public void launcherSessionOpened(@NonNull final LauncherSession session) {
        session.getLauncher().registerTestExecutionListeners(new SharedMultiNetworkExecutionListener());
    }

    public class SharedMultiNetworkExecutionListener implements TestExecutionListener {
        @Override
        public void testPlanExecutionStarted(@NonNull final TestPlan testPlan) {
            // Walk the test plan and collect every {@code @MultiNetworkHapiTest} annotation
            // declaration, grouped by network name. Each declaration carries its source location so we
            // can point back to the offending test when reporting network's first port conflicts.
            final var stack = new ArrayDeque<>(testPlan.getRoots());
            while (!stack.isEmpty()) {
                final var id = stack.pop();
                testPlan.getChildren(id).forEach(stack::push);
                id.getSource().ifPresent(source -> {
                    if (source instanceof MethodSource ms) {
                        final var clazz = tryLoad(ms.getClassName());
                        if (clazz == null) {
                            return;
                        }
                        for (final var m : clazz.getDeclaredMethods()) {
                            if (!m.getName().equals(ms.getMethodName())) {
                                continue;
                            }
                            collectDeclarations(clazz.getName(), m);
                        }
                    } else if (source instanceof ClassSource cs) {
                        final var clazz = tryLoad(cs.getClassName());
                        if (clazz == null) {
                            return;
                        }
                        for (final var m : clazz.getDeclaredMethods()) {
                            collectDeclarations(clazz.getName(), m);
                        }
                    }
                });
            }
            if (declarationsByName.isEmpty()) {
                return;
            }

            // Resolve per-name conflicts and pick a canonical Network config: throws
            // IllegalStateException if the same name is declared with two different explicit
            // firstGrpcPort values, naming both declaration sites.
            final LinkedHashMap<String, MultiNetworkHapiTest.Network> byName = new LinkedHashMap<>();
            declarationsByName.forEach((name, declarations) -> byName.put(name, resolveNetwork(name, declarations)));

            // Sort so networks with explicit ports come first: the reservation tracker in
            // MultiNetworkExtension.resolveFirstGrpcPort then claims those exact ports before
            // any auto-allocated network scans for free slots — auto slots will skip over any
            // window occupied by an explicit reservation.
            final var configs = byName.values().stream()
                    .sorted(Comparator.<MultiNetworkHapiTest.Network>comparingInt(n -> n.firstGrpcPort() > 0 ? 0 : 1)
                            .thenComparing(MultiNetworkExtension::resolveName))
                    .toArray(MultiNetworkHapiTest.Network[]::new);

            final SubProcessNetwork[] networks = MultiNetworkExtension.startNetworks(configs);
            if (networks.length > 0) {
                reconfigureSharedSubProcessLogging(networks[0]);
            }
            log.info("Starting shared multi-networks: {}", byName.keySet());
            for (int i = 0; i < configs.length; i++) {
                MultiNetworkExtension.SHARED_NETWORKS.put(MultiNetworkExtension.resolveName(configs[i]), networks[i]);
            }
        }

        /** One occurrence of a {@code @MultiNetworkHapiTest.Network} annotation on a test method. */
        private record NetworkDeclaration(MultiNetworkHapiTest.Network network, String source) {}

        private void collectDeclarations(@NonNull final String className, @NonNull final Method method) {
            // A test node can appear both as a MethodSource and via its enclosing ClassSource;
            // dedupe by fully-qualified method to avoid double-counting.
            final String methodKey = className + "#" + method.getName();
            if (!seenMethods.add(methodKey)) {
                return;
            }
            AnnotationSupport.findAnnotation(method, MultiNetworkHapiTest.class).ifPresent(ann -> {
                for (final var n : ann.value()) {
                    declarationsByName
                            .computeIfAbsent(MultiNetworkExtension.resolveName(n), k -> new ArrayList<>())
                            .add(new NetworkDeclaration(n, methodKey));
                }
            });
        }

        private static MultiNetworkHapiTest.Network resolveNetwork(
                @NonNull final String name, @NonNull final List<NetworkDeclaration> declarations) {
            // Bucket declarations by their explicit firstGrpcPort (>0). Two distinct explicit
            // values for the same network name is a hard conflict — the launcher-session
            // listener can only start one subprocess per name, so the test author must pick.
            final Map<Integer, List<NetworkDeclaration>> byExplicitPort = new LinkedHashMap<>();
            for (final NetworkDeclaration d : declarations) {
                final int port = d.network().firstGrpcPort();
                if (port > 0) {
                    byExplicitPort.computeIfAbsent(port, k -> new ArrayList<>()).add(d);
                }
            }
            if (byExplicitPort.size() > 1) {
                final var sb = new StringBuilder("Network '")
                        .append(name)
                        .append("' declared with conflicting explicit firstGrpcPort values:");
                byExplicitPort.forEach((port, srcList) -> {
                    sb.append("\n  - firstGrpcPort=").append(port).append(" declared at:");
                    for (final NetworkDeclaration d : srcList) {
                        sb.append("\n      ").append(d.source());
                    }
                });
                sb.append("\nEither harmonize the ports across tests using this network, or leave them unset "
                        + "(firstGrpcPort=-1) so the extension auto-allocates a slot for the shared network.");
                throw new IllegalStateException(sb.toString());
            }
            // Prefer the explicit-port declaration when there is one — it wins over any bare
            // (firstGrpcPort=-1) declaration for the same name. Otherwise take the first
            // declaration in test-plan discovery order.
            if (!byExplicitPort.isEmpty()) {
                return byExplicitPort.values().iterator().next().get(0).network();
            }
            return declarations.get(0).network();
        }

        private static void reconfigureSharedSubProcessLogging(@NonNull final SubProcessNetwork network) {
            // Route the test-client driver log to a sibling of each network's working dir
            // (e.g. `build/multinetwork-test-clients/`), not inside any one network's node0 output.
            // The first node's working dir is `build/<scope>-test/node0`; two parents up is the
            // gradle build root. Subtask-name nesting is disabled for MULTINETWORK in
            // build.gradle.kts so the depth is fixed.
            final var workingDir = network.nodes()
                    .getFirst()
                    .getExternalPath(ExternalPath.WORKING_DIR)
                    .toAbsolutePath()
                    .normalize();
            final Path outputDir;
            try {
                outputDir = workingDir.getParent().getParent().resolve(MULTINETWORK_LOG_DIR);
                Files.createDirectories(outputDir);
            } catch (final RuntimeException | IOException e) {
                log.warn("Could not resolve multi-network test-client log dir from '{}'", workingDir, e);
                return;
            }
            System.setProperty(
                    TEST_CLIENT_LOG_FILE, outputDir.resolve("test-clients.log").toString());
            System.setProperty(
                    TEST_CLIENT_LOG_FILE_PATTERN,
                    outputDir.resolve("test-clients-%d{yyyy-MM-dd}-%i.log").toString());
            // Reconfigure in place using log4j2-test-client.xml with multi-network output paths.
            Configurator.reconfigure();
            log.info("Configured shared multi-network test-client logging under {}", outputDir);
        }

        @Override
        public void testPlanExecutionFinished(@NonNull final TestPlan testPlan) {
            for (final var n : MultiNetworkExtension.SHARED_NETWORKS.values()) {
                MultiNetworkExtension.safeTerminate(n);
            }
            MultiNetworkExtension.SHARED_NETWORKS.clear();
        }

        private static Class<?> tryLoad(@NonNull final String className) {
            final var ctx = Thread.currentThread().getContextClassLoader();
            try {
                return Class.forName(
                        className,
                        false,
                        ctx != null ? ctx : SharedMultiNetworkExecutionListener.class.getClassLoader());
            } catch (ClassNotFoundException e) {
                try {
                    return Class.forName(className);
                } catch (ClassNotFoundException exception) {
                    log.warn("Could not load test class '{}'. Skipping multi-network discovery.", className, exception);
                    return null;
                }
            }
        }
    }
}
