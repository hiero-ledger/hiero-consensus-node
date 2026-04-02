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
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
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

    private final HederaNetwork targetNetwork;

    private Class<?> currentTestClass = null;

    public TestLifecycle(@NonNull final HederaNetwork targetNetwork) {
        this.targetNetwork = requireNonNull(targetNetwork);
    }

    /**
     * Overrides the given properties in the current test class, to be restored to their previous
     * values after the class completes.
     *
     * @param overrides the class-scoped overrides
     */
    public void overrideInClass(@NonNull final Map<String, String> overrides) {
        final Map<String, String> preservedProperties = new HashMap<>();
        doAdhoc(remembering(preservedProperties, overrides.keySet().stream().toList()), overridingAllOf(overrides));
        deque.push(new Memories(preservedProperties, requireNonNull(currentTestClass)));
    }

    /**
     * Does the given operations against the target network.
     *
     * @param ops the operations to do
     */
    public void doAdhoc(@NonNull final SpecOperation... ops) {
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
        return targetNetwork.nodes();
    }
}
