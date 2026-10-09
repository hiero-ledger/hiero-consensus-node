// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit;

import static java.util.stream.Collectors.toCollection;
import static org.junit.platform.commons.support.AnnotationSupport.findAnnotation;

import com.hedera.services.bdd.junit.extensions.MultiNetworkExtension;
import com.hedera.services.bdd.junit.extensions.MultiNetworkGroupQueue;
import com.hedera.services.bdd.junit.extensions.NetworkGroup;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.platform.commons.support.AnnotationSupport;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
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

    /** Every test class in the plan, including {@code @Disabled} ones (JUnit still takes their locks). */
    private final Set<Class<?>> testClasses = new LinkedHashSet<>();

    @Override
    public void launcherSessionOpened(@NonNull final LauncherSession session) {
        session.getLauncher().registerTestExecutionListeners(new SharedMultiNetworkExecutionListener());
    }

    /**
     * Fails every network group locked by a test class that locks the networks of more than one group.
     *
     * <p>JUnit holds a class's {@code @ResourceLock}s while its tests run, even for a {@code @Disabled} class. If a
     * class holds a lock of group X while one of its tests waits in {@link MultiNetworkGroupQueue} for group X to
     * finish, and another class of group X needs that lock, no test can move. Allowing each class to lock only one
     * group's networks rules this out. Locks on names that are not multi-network networks are ignored.
     *
     * @param classes the test classes in the plan
     * @param queue the queue whose groups to fail
     */
    static void validateOneGroupPerClass(
            @NonNull final Collection<Class<?>> classes, @NonNull final MultiNetworkGroupQueue queue) {
        final Map<String, String> groupByNetwork = new HashMap<>();
        classes.stream()
                .flatMap(clazz -> Arrays.stream(clazz.getDeclaredMethods()))
                .flatMap(method -> findAnnotation(method, MultiNetworkHapiTest.class).stream())
                .map(ann -> NetworkGroup.of(ann.value()))
                .forEach(group -> group.networkNames().forEach(name -> groupByNetwork.putIfAbsent(name, group.id())));
        for (final var clazz : classes) {
            final Set<String> lockedNetworks = Stream.concat(
                            Stream.<AnnotatedElement>of(clazz), Arrays.stream(clazz.getDeclaredMethods()))
                    .flatMap(element ->
                            AnnotationSupport.findRepeatableAnnotations(element, ResourceLock.class).stream())
                    .map(ResourceLock::value)
                    .filter(groupByNetwork::containsKey)
                    .collect(toCollection(TreeSet::new));
            final Set<String> lockedGroups =
                    lockedNetworks.stream().map(groupByNetwork::get).collect(toCollection(TreeSet::new));
            if (lockedGroups.size() > 1) {
                final var error = "Test class " + clazz.getName() + " locks networks of several network groups "
                        + lockedGroups + "; a class may lock only one group's networks, or it can deadlock";
                log.error(error);
                queue.invalidate(lockedNetworks, error);
            }
        }
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
                        testClasses.add(clazz);
                        for (final var m : clazz.getDeclaredMethods()) {
                            if (!m.getName().equals(ms.getMethodName())) {
                                continue;
                            }
                            collectDeclarations(clazz.getName(), m);
                            registerTest(id.getUniqueId(), m);
                        }
                    } else if (source instanceof ClassSource cs) {
                        final var clazz = tryLoad(cs.getClassName());
                        if (clazz == null) {
                            return;
                        }
                        testClasses.add(clazz);
                        for (final var m : clazz.getDeclaredMethods()) {
                            collectDeclarations(clazz.getName(), m);
                        }
                    }
                });
            }
            if (declarationsByName.isEmpty()) {
                return;
            }
            validateOneGroupPerClass(testClasses, MultiNetworkExtension.NETWORK_GROUP_QUEUE);

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
            findAnnotation(method, MultiNetworkHapiTest.class).ifPresent(ann -> {
                // Skip @Disabled tests: they never execute, so their networks must not be reserved
                if (isDisabled(method)) {
                    return;
                }
                for (final var n : ann.value()) {
                    declarationsByName
                            .computeIfAbsent(MultiNetworkExtension.resolveName(n), k -> new ArrayList<>())
                            .add(new NetworkDeclaration(n, methodKey));
                }
            });
        }

        /**
         * Registers the enabled test {@code testId} with the network group its {@code @Network}s name, so the
         * group is admitted under the node budget and torn down after its last test.
         */
        private static void registerTest(@NonNull final String testId, @NonNull final Method method) {
            if (isDisabled(method)) {
                return;
            }
            findAnnotation(method, MultiNetworkHapiTest.class)
                    .ifPresent(ann ->
                            MultiNetworkExtension.NETWORK_GROUP_QUEUE.register(testId, NetworkGroup.of(ann.value())));
        }

        private static boolean isDisabled(@NonNull final Method method) {
            return AnnotationSupport.isAnnotated(method, Disabled.class)
                    || AnnotationSupport.isAnnotated(method.getDeclaringClass(), Disabled.class);
        }

        @Override
        public void executionSkipped(@NonNull final TestIdentifier testIdentifier, @NonNull final String reason) {
            // Skipped tests never reach afterEach, so count them as finished here
            MultiNetworkExtension.finishSharedTests(testIdentifier.getUniqueId());
        }

        @Override
        public void executionFinished(
                @NonNull final TestIdentifier testIdentifier, @NonNull final TestExecutionResult result) {
            // Tests that never ran (e.g. their class's @BeforeAll failed) never reach afterEach, so count them as
            // finished once their container finishes
            MultiNetworkExtension.finishSharedTests(testIdentifier.getUniqueId());
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
            MultiNetworkExtension.NETWORK_GROUP_QUEUE.clear();
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
