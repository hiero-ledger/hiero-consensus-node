// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support;

import static com.hedera.services.bdd.spec.HapiSpec.doTargetSpec;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingAllOf;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.remembering;
import static java.util.Objects.requireNonNull;

import com.hedera.services.bdd.junit.hedera.HederaNetwork;
import com.hedera.services.bdd.junit.hedera.HederaNode;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.infrastructure.SpecStateObserver;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Concurrency notes for nested test classes using injected {@link TestLifecycle}:
 * <ul>
 *     <li>{@link #doAdhoc(SpecOperation...)} is safe to call from nested {@code @BeforeAll} methods in concurrently
 *     executing nested classes.</li>
 *     <li>{@link #overrideInClass(Map)} is class-scoped and not safe when called from nested {@code @BeforeAll}
 *     methods in concurrently executing nested classes. Such root test classes should be annotated with
 *     {@code @OrderedInIsolation} and run serially.</li>
 *     <li>If a root class has a root {@code @BeforeAll} plus nested {@code @BeforeAll} methods, concurrent execution
 *     is supported only when nested {@code @BeforeAll} methods do not call {@link #overrideInClass(Map)}. The root
 *     {@code @BeforeAll} may call {@link #overrideInClass(Map)}.</li>
 * </ul>
 */
public class TestLifecycle {
    private static final String SPEC_NAME = "<MANAGED>";

    private record Memories(Map<String, String> preservedProperties, Class<?> testClass) {}

    private final Deque<Memories> deque = new ArrayDeque<>();

    private final List<SpecStateObserver.SpecState> sharedStates = new CopyOnWriteArrayList<>();

    /**
     * Class-level overrides recorded while no target network exists yet (e.g. when the only tests in the
     * class create their networks per-method, as with {@code @GenesisSubprocessTest}). These are surfaced
     * via {@link #deferredClassOverrides()} so per-method network creation can apply them at boot time.
     */
    private final Map<String, String> deferredClassOverrides = new LinkedHashMap<>();

    @Nullable
    private final HederaNetwork targetNetwork;

    private Class<?> currentTestClass = null;

    public TestLifecycle(@Nullable final HederaNetwork targetNetwork) {
        this.targetNetwork = targetNetwork;
    }

    /**
     * Overrides the given properties in the current test class, to be restored to their previous
     * values after the class completes.
     *
     * @param overrides the class-scoped overrides
     */
    public void overrideInClass(@NonNull final Map<String, String> overrides) {
        if (targetNetwork == null) {
            // No class-scoped network exists (e.g. a @GenesisSubprocessTest class whose networks are created
            // per-method); record the overrides so the per-method network creation can apply them at boot.
            deferredClassOverrides.putAll(overrides);
            return;
        }
        final Map<String, String> preservedProperties = new HashMap<>();
        doAdhoc(remembering(preservedProperties, overrides.keySet().stream().toList()), overridingAllOf(overrides));
        deque.push(new Memories(preservedProperties, requireNonNull(currentTestClass)));
    }

    /**
     * Returns the class-level overrides recorded while no target network was available, to be applied by
     * per-method network creation. Empty when there are none.
     *
     * @return the deferred class-level overrides
     */
    public Map<String, String> deferredClassOverrides() {
        return deferredClassOverrides;
    }

    /**
     * Does the given operations against the target network.
     *
     * @param ops the operations to do
     */
    public void doAdhoc(@NonNull final SpecOperation... ops) {
        if (targetNetwork == null) {
            // Nothing to target; this lifecycle defers class overrides instead of running them ad hoc.
            return;
        }
        final var spec = new HapiSpec(SPEC_NAME, ops);
        doTargetSpec(spec, targetNetwork);
        spec.setSpecStateObserver(sharedStates::add);
        try {
            spec.execute();
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Restores the overridden properties for the given test class, if any.
     *
     * @param finishedTestClass the class whose properties are to be restored
     */
    public void restoreAnyOverriddenProperties(@NonNull final Class<?> finishedTestClass) {
        if (!deque.isEmpty() && deque.peek().testClass() == finishedTestClass) {
            doAdhoc(overridingAllOf(deque.pop().preservedProperties()));
        }
    }

    /**
     * Sets the current test class.
     *
     * @param currentTestClass the current test class
     */
    public void setCurrentTestClass(@NonNull final Class<?> currentTestClass) {
        this.currentTestClass = requireNonNull(currentTestClass);
    }

    public List<SpecStateObserver.SpecState> getSharedStates() {
        return sharedStates;
    }

    /**
     * Gets the nodes in the target network.
     *
     * @return the nodes
     */
    public List<HederaNode> getNodes() {
        return targetNetwork == null ? List.of() : targetNetwork.nodes();
    }
}
