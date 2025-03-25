// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.constructable.internal;

import com.swirlds.common.constructable.ConstructableClass;
import com.swirlds.common.constructable.ConstructableIgnored;
import com.swirlds.common.constructable.NoArgsConstructor;
import com.swirlds.common.constructable.URLClassLoaderWithLookup;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ScanResult;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.hiero.consensus.model.constructable.RuntimeConstructable;

/**
 * Scans the classpath for {@link RuntimeConstructable} classes
 */
public final class ConstructableScanner {
    private ConstructableScanner() {}

    /**
     * Searches the classpath for all {@link RuntimeConstructable} classes.
     * <p>
     * The method will search the classpath for any non-abstract classes that implement {@link RuntimeConstructable} and
     * sort them by their constructor type.
     *
     * @param packagePrefix
     * 		the package prefix of classes to search for, can be an empty String to search all packages
     * @param additionalClassloader
     * 		if any classes are loaded by a non-system classloader, it must be provided to find those classes
     */
    public static Collection<ConstructableClasses<?>> getConstructableClasses(
            final String packagePrefix, final URLClassLoaderWithLookup additionalClassloader) {
        final Map<Class<?>, ConstructableClasses<?>> map = new HashMap<>();
        final ClassGraph classGraph = new ClassGraph().enableClassInfo().whitelistPackages(packagePrefix);
        if (additionalClassloader != null) {
            classGraph.addClassLoader(additionalClassloader);
        }
        try (final ScanResult scanResult = classGraph.scan()) {
            for (final ClassInfo classInfo :
                    scanResult.getClassesImplementing(RuntimeConstructable.class.getCanonicalName())) {
                final Class<? extends RuntimeConstructable> subType = classInfo.loadClass(RuntimeConstructable.class);
                if (isSkippable(subType)) {
                    continue;
                }

                final Class<?> constructorType = getConstructorType(subType);
                map.computeIfAbsent(constructorType, ConstructableClasses::new).addClass(subType);
            }
        }

        return map.values();
    }

    private static boolean isSkippable(final Class<? extends RuntimeConstructable> subType) {
        return subType.isInterface()
                || Modifier.isAbstract(subType.getModifiers())
                || subType.isAnnotationPresent(ConstructableIgnored.class);
    }

    private static Class<?> getConstructorType(final Class<? extends RuntimeConstructable> subType) {
        return subType.isAnnotationPresent(ConstructableClass.class)
                ? subType.getAnnotation(ConstructableClass.class).constructorType()
                : NoArgsConstructor.class;
    }
}
