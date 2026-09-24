// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit;

import com.hedera.services.bdd.junit.extensions.MultiNetworkExtension;
import com.hedera.services.bdd.junit.extensions.MultiNetworkGroupBudget;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.lang.reflect.Method;
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
import org.junit.jupiter.api.Disabled;
import org.junit.platform.commons.support.AnnotationSupport;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestPlan;

/**
 * JUnit Platform launcher-session listener that runs once per test plan, before any tests execute.
 * Scans the plan for {@link MultiNetworkHapiTest} annotations on both classes and methods and, for each
 * distinct network name (e.g. {@code ledgerA}, {@code ledgerB}), reserves its port window and records the
 * canonical {@code @Network} config in {@link MultiNetworkExtension#DECLARED_CONFIGS}. It does NOT boot
 * nodes here: each shared network is started lazily on first demand by
 * {@link MultiNetworkExtension#getOrStartShared} and kept warm in
 * {@link MultiNetworkExtension#SHARED_NETWORKS} for reuse. Terminates all started networks after the plan
 * finishes.
 */
public class SharedMultiNetworkLauncherSessionListener implements LauncherSessionListener {
    private static final Logger log = LogManager.getLogger(SharedMultiNetworkLauncherSessionListener.class);

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

            // Lazy start: do NOT boot nodes here. Reserve each declared network's port window
            // up front (explicit-port networks first, per the sort above) so networks that start lazily
            // and out of order can't collide, and record the canonical @Network config per name. Each
            // network boots on first demand in MultiNetworkExtension.getOrStartShared and stays warm for
            // reuse; the shared driver log is reconfigured on the first one to boot.
            MultiNetworkExtension.reservePorts(configs);
            for (final var cfg : configs) {
                MultiNetworkExtension.DECLARED_CONFIGS.put(MultiNetworkExtension.resolveName(cfg), cfg);
            }
            log.info("Declared shared multi-networks (lazy start): {}", byName.keySet());
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
                // Skip @Disabled tests: they never execute, so their networks must not be reserved or
                // counted. Counting a test that never runs would leave its network group's pending count above
                // zero forever, leaking the network group's budget slot; excluding them also means a fully
                // @Disabled suite is never admitted and its networks never boot.
                if (method.isAnnotationPresent(Disabled.class)
                        || method.getDeclaringClass().isAnnotationPresent(Disabled.class)) {
                    return;
                }
                for (final var n : ann.value()) {
                    declarationsByName
                            .computeIfAbsent(MultiNetworkExtension.resolveName(n), k -> new ArrayList<>())
                            .add(new NetworkDeclaration(n, methodKey));
                }
                // One beforeEach/afterEach fires per factory method, so count one pending test for the
                // network group this method occupies (its @Network name-set). The budget frees the network group's slot
                // when this count reaches zero.
                MultiNetworkGroupBudget.registerPendingTest(ann.value());
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

        @Override
        public void testPlanExecutionFinished(@NonNull final TestPlan testPlan) {
            for (final var n : MultiNetworkExtension.SHARED_NETWORKS.values()) {
                MultiNetworkExtension.safeTerminate(n);
            }
            MultiNetworkExtension.SHARED_NETWORKS.clear();
            MultiNetworkExtension.DECLARED_CONFIGS.clear();
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
