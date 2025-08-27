// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.platform.commons.support.AnnotationSupport;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.CompositeTestSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestPlan;

public final class TestPlanUtils {
    private TestPlanUtils() {}

    /**
     * Returns true iff there exists a test node in the plan whose underlying
     * element (test method or test class) is annotated with any of the given types.
     * Semantics:
     * - Only nodes where {@code TestIdentifier#isTest()} is true are considered.
     * - For method-backed tests, both the method and its declaring class are checked.
     * - Meta-annotations (composed annotations) are honored via AnnotationSupport.
     * - Nodes that do not expose a {@link MethodSource}, {@link ClassSource}, or
     *   {@link CompositeTestSource} are ignored.
     */
    public static boolean hasAnnotatedTestNode(
            @NonNull final TestPlan plan, @NonNull final Collection<Class<? extends Annotation>> annotationTypes) {
        final Set<Class<? extends Annotation>> types = new HashSet<>(annotationTypes);
        if (types.isEmpty()) {
            return false;
        }
        final var stack = new ArrayDeque<>(plan.getRoots());
        while (!stack.isEmpty()) {
            final var id = stack.pop();
            // Traverse the tree
            plan.getChildren(id).forEach(stack::push);
            final var optSource = id.getSource();
            if (optSource.isEmpty()) {
                continue;
            }
            if (sourceHasAnyAnnotation(optSource.get(), types)) {
                return true;
            }
        }
        return false;
    }

    // --- helpers ---
    private static boolean sourceHasAnyAnnotation(
            @NonNull final TestSource source, @NonNull final Set<Class<? extends Annotation>> types) {
        switch (source) {
            case MethodSource ms -> {
                final var testClass = tryLoad(ms.getClassName());
                if (testClass == null) {
                    return false;
                }
                // Class-level annotation?
                if (isAnnotatedWithAny(testClass, types)) {
                    return true;
                }
                // Method-level annotation? (handle overloading by scanning all with the same name)
                for (final var m : findCandidateMethods(testClass, ms.getMethodName())) {
                    if (isAnnotatedWithAny(m, types)) {
                        return true;
                    }
                }
                return false;
            }
            case ClassSource cs -> {
                final var testClass = tryLoad(cs.getClassName());
                return testClass != null && isAnnotatedWithAny(testClass, types);
            }
            case CompositeTestSource composite -> {
                for (final var nested : composite.getSources()) {
                    if (sourceHasAnyAnnotation(nested, types)) {
                        return true;
                    }
                }
                return false;
            }
            default -> {
                // Other source types (files, packages, etc.) are not resolvable to AnnotatedElements.
                return false;
            }
        }
    }

    private static boolean isAnnotatedWithAny(
            @NonNull final AnnotatedElement element, @NonNull final Set<Class<? extends Annotation>> types) {
        for (final Class<? extends Annotation> type : types) {
            if (AnnotationSupport.isAnnotated(element, type)) {
                return true;
            }
        }
        return false;
    }

    private static Class<?> tryLoad(@NonNull final String className) {
        final var ctx = Thread.currentThread().getContextClassLoader();
        try {
            return Class.forName(className, false, ctx != null ? ctx : TestPlanUtils.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            try {
                return Class.forName(className);
            } catch (ClassNotFoundException ignored) {
                return null;
            }
        }
    }

    private static List<Method> findCandidateMethods(@NonNull final Class<?> clazz, @NonNull final String methodName) {
        final List<Method> result = new ArrayList<>();
        for (var c = clazz; c != null; c = c.getSuperclass()) {
            for (var m : c.getDeclaredMethods()) {
                if (m.getName().equals(methodName)) {
                    result.add(m);
                }
            }
        }
        return result;
    }
}
