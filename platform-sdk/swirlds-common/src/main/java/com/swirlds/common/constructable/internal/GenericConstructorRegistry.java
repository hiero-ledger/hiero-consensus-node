// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.constructable.internal;

import com.swirlds.common.Releasable;
import com.swirlds.common.constructable.ConstructableClass;
import com.swirlds.common.constructable.ConstructableRegistryException;
import com.swirlds.common.constructable.ConstructorRegistry;
import com.swirlds.common.constructable.NoArgsConstructor;
import com.swirlds.common.constructable.URLClassLoaderWithLookup;
import com.swirlds.common.exceptions.NotImplementedException;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.lang.invoke.CallSite;
import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.hiero.consensus.model.constructable.RuntimeConstructable;

/**
 * A {@link ConstructorRegistry} which has the constructor type as a generic
 *
 * @param <T>
 * 		the type of constructor used by this registry
 */
public class GenericConstructorRegistry<T> implements ConstructorRegistry<T> {
    private static final Set<String> IGNORE_METHOD_NAMES = Set.of("$jacocoInit");

    private final Class<T> constructorType;
    private final Method constructorSignature;

    /** A map that holds the constructors of all RuntimeConstructable classes */
    private final Map<Long, GenericClassConstructorPair<T>> constructors = new ConcurrentHashMap<>();

    /**
     * @param constructorType
     * 		the type of constructor for this registry to use
     */
    public GenericConstructorRegistry(@NonNull final Class<T> constructorType) {
        Objects.requireNonNull(constructorType, "constructorType must not be null");
        this.constructorType = constructorType;

        if (!constructorType.isInterface()) {
            throw new IllegalArgumentException(String.format(
                    "The constructor type needs to be an interface, '%s' is not an interface",
                    constructorType.getCanonicalName()));
        }

        this.constructorSignature = getMethod(constructorType);
        if (!RuntimeConstructable.class.isAssignableFrom(constructorSignature.getReturnType())) {
            throw new IllegalArgumentException("The constructor return type must extend RuntimeConstructable");
        }
    }

    @Override
    public T getConstructor(final long classId) {
        final GenericClassConstructorPair<T> p = constructors.get(classId);
        if (p == null) {
            return null;
        }
        return p.constructor();
    }

    /**
     * Generate lambdas and register all classes provided
     *
     * @param constructableClasses
     * 		the classes to register
     * @param additionalClassloader
     * 		a non-default classloader used
     * @throws ConstructableRegistryException
     * 		if any class has an issue being registered
     */
    public void registerConstructables(
            final ConstructableClasses<?> constructableClasses, final URLClassLoaderWithLookup additionalClassloader)
            throws ConstructableRegistryException {
        if (!constructableClasses.getConstructorType().equals(constructorType)) {
            throw new ConstructableRegistryException(String.format(
                    "Cannot register the constructor type %s, this registry requires %s",
                    constructableClasses.getConstructorType().getCanonicalName(), constructorType.getCanonicalName()));
        }
        for (final Class<? extends RuntimeConstructable> constructable : constructableClasses.getClasses()) {
            registerConstructable(constructable, createConstructorLambda(constructable, additionalClassloader));
        }
    }

    @Override
    public void registerConstructable(final Class<? extends RuntimeConstructable> aClass)
            throws ConstructableRegistryException {
        registerConstructable(aClass, createConstructorLambda(aClass, null));
    }

    @Override
    public void registerConstructable(final Class<? extends RuntimeConstructable> aClass, final T constructor)
            throws ConstructableRegistryException {
        final GenericClassConstructorPair<T> pair = new GenericClassConstructorPair<>(aClass, constructor);
        final long classId = getClassId(pair);
        final GenericClassConstructorPair<T> old = constructors.putIfAbsent(classId, pair);
        if (old != null && !old.classEquals(pair)) {
            throw new ConstructableRegistryException(String.format(
                    "Two classes ('%s' and '%s') have the same classId:%d (hex:%s). " + "ClassId must be unique!",
                    old.constructable().getCanonicalName(),
                    pair.constructable().getCanonicalName(),
                    classId,
                    Long.toHexString(classId)));
        }
    }

    private long getClassId(final GenericClassConstructorPair<T> pair) throws ConstructableRegistryException {
        // before the ConstructableClass annotation was introduced, the only to get the class ID was to create an
        // instance of the class. this will be removed when all classes start using the annotation
        if (pair.constructor() instanceof NoArgsConstructor nac) {
            final RuntimeConstructable obj = nac.get();
            final long classId = obj.getClassId();
            if (obj instanceof Releasable) {
                try {
                    ((Releasable) obj).release();
                } catch (final NotImplementedException ignored) {
                    // ignore it
                }
            }
            return classId;
        } else {
            final ConstructableClass classIdAnnotation = pair.constructable().getAnnotation(ConstructableClass.class);
            if (classIdAnnotation == null) {
                throw new ConstructableRegistryException(String.format(
                        "The class %s must have a @ConstructableClass annotation",
                        pair.constructable().getName()));
            }
            return classIdAnnotation.value();
        }
    }

    private T createConstructorLambda(
            final Class<? extends RuntimeConstructable> constructable,
            final URLClassLoaderWithLookup additionalClassloader)
            throws ConstructableRegistryException {
        if (!hasRequiredConstructor(constructable)) {
            throw new ConstructableRegistryException(String.format(
                    "Cannot find appropriate constructor for class: %s", constructable.getCanonicalName()));
        }
        final T constructor;
        try {
            GenericConstructorRegistry.class.getModule().addReads(constructable.getModule());
            final MethodHandles.Lookup lookup = getLookup(constructable, additionalClassloader);

            final Class<?>[] params = constructorSignature.getParameterTypes();
            final MethodHandle mh = lookup.findConstructor(constructable, MethodType.methodType(void.class, params));

            final CallSite site = LambdaMetafactory.metafactory(
                    lookup,
                    constructorSignature.getName(),
                    MethodType.methodType(constructorType),
                    MethodType.methodType(constructorSignature.getReturnType(), params),
                    mh,
                    mh.type());
            constructor = constructorType.cast(site.getTarget().invoke());
        } catch (final Throwable throwable) {
            throw new ConstructableRegistryException(
                    String.format("Could not create a lambda for constructor: %s", throwable.getMessage()), throwable);
        }
        return constructor;
    }

    /**
     * Depending on which classloader loaded the class, the Lookup object should come from the context of that
     * classloader. So we check which loader loaded the class to get the appropriate lookup object.
     */
    private static MethodHandles.Lookup getLookup(
            final Class<? extends RuntimeConstructable> constructable,
            final URLClassLoaderWithLookup additionalClassloader)
            throws ConstructableRegistryException {
        if (additionalClassloader != null && additionalClassloader.equals(constructable.getClassLoader())) {
            try {
                return additionalClassloader.getLookup();
            } catch (final RuntimeException
                    | ClassNotFoundException
                    | NoSuchFieldException
                    | IllegalAccessException e) {
                throw new ConstructableRegistryException(
                        "Issue while getting the MethodHandles.Lookup from URLClassLoaderWithLookup", e);
            }
        }
        return MethodHandles.lookup();
    }

    private boolean hasRequiredConstructor(final Class<? extends RuntimeConstructable> constructable) {
        return Stream.of(constructable.getConstructors())
                .anyMatch(c -> Arrays.equals(c.getParameterTypes(), constructorSignature.getParameterTypes()));
    }

    private static Method getMethod(final Class<?> constructorType) {
        // methods that are overridden will appear more than once, so we filter them out
        final Method[] methods = Arrays.stream(constructorType.getDeclaredMethods())
                .filter(m -> !IGNORE_METHOD_NAMES.contains(m.getName()))
                .map(MethodWrapper::new)
                .distinct()
                .map(MethodWrapper::method)
                .toArray(Method[]::new);

        if (methods.length != 1) {
            throw new IllegalArgumentException(String.format(
                    "The constructor type '%s' must have exactly 1 method,"
                            + " but the following methods have been found:%n%s",
                    constructorType.getName(),
                    Arrays.stream(methods)
                            .map(m -> m.getReturnType().getSimpleName()
                                    + " "
                                    + m.getName()
                                    + "("
                                    + Arrays.stream(m.getParameterTypes())
                                            .map(Class::getSimpleName)
                                            .collect(Collectors.joining(" "))
                                    + ")")
                            .collect(Collectors.joining("\n"))));
        }
        return methods[0];
    }
}
