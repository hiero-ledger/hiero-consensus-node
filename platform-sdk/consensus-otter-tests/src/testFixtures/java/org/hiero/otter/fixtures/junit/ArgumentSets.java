// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.junit;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.lang.annotation.Annotation;
import java.lang.invoke.MethodType;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.stream.BaseStream;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.hiero.otter.fixtures.TestEnvironment;
import org.junit.jupiter.api.extension.ExtensionConfigurationException;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.junit.jupiter.params.provider.ArgumentsSources;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.platform.commons.support.AnnotationSupport;
import org.junit.platform.commons.support.ReflectionSupport;

/**
 * Reads the argument sets of a test method from the argument sources declared on it.
 *
 * <p>The argument sources are JUnit's own annotations, but they are read as plain data rather than through JUnit's
 * {@code ArgumentsProvider}s, which can only be driven through internal API and whose contract changes in later JUnit
 * versions. Only a subset is supported:
 * <ul>
 *     <li>{@link MethodSource} naming static factory methods without parameters in the test class or one of its
 *     superclasses. An empty name refers to the factory method with the same name as the test method. A factory method
 *     may return a {@link Stream} (including primitive streams), an {@link Iterable}, an {@link Iterator} or an array.
 *     Each element is either an {@link Arguments}, an {@code Object[]}, or a single value.</li>
 *     <li>{@link ValueSource} with exactly one attribute set. Each element is a single value.</li>
 * </ul>
 * Any other argument source is rejected. The values of an argument set bind to the leading parameters of the test
 * method in order and must already have the type of their parameter; there is no implicit conversion. A
 * {@link TestEnvironment} parameter must come after them.
 *
 * <p>The argument sets are evaluated once, before any invocation runs, so a mistake surfaces as a single error instead
 * of failing every invocation.
 */
final class ArgumentSets {

    private ArgumentSets() {}

    /**
     * Reads the argument sets of the test method of the given context.
     *
     * @param context the extension context of the test method
     * @return the argument sets in the order of their sources, or a list holding only {@link ArgumentSet#EMPTY} if the
     * test method declares no argument source
     * @throws ExtensionConfigurationException if an argument source is not supported or does not match the test method
     */
    @NonNull
    static List<ArgumentSet> of(@NonNull final ExtensionContext context) {
        final Method testMethod = context.getRequiredTestMethod();
        final List<ArgumentSet> argumentSets = new ArrayList<>();
        boolean hasSource = false;
        for (final Annotation annotation : testMethod.getAnnotations()) {
            switch (annotation) {
                case final MethodSource methodSource -> {
                    hasSource = true;
                    readMethodSource(context, methodSource, argumentSets);
                }
                case final ValueSource valueSource -> {
                    hasSource = true;
                    readValueSource(testMethod, valueSource, argumentSets);
                }
                default -> {
                    if (isArgumentsSource(annotation)) {
                        throw configurationError(
                                testMethod,
                                "uses the unsupported argument source @%s, only @%s and @%s are supported"
                                        .formatted(
                                                annotation.annotationType().getSimpleName(),
                                                MethodSource.class.getSimpleName(),
                                                ValueSource.class.getSimpleName()));
                    }
                }
            }
        }

        if (!hasSource) {
            return List.of(ArgumentSet.EMPTY);
        }
        if (argumentSets.isEmpty()) {
            throw configurationError(testMethod, "must provide at least one argument set");
        }
        for (int i = 0; i < argumentSets.size(); i++) {
            validate(testMethod, i + 1, argumentSets.get(i));
        }
        return argumentSets;
    }

    /**
     * Rejects a test method that is also annotated with {@link ParameterizedTest}. JUnit would run the parameterized
     * invocations in addition to the ones of the owning extension, which provides the argument sets itself.
     *
     * <p>This must be called from {@code supportsTestTemplate()}, because JUnit asks every provider whether it supports
     * a test template before it runs any invocation.
     *
     * @param context the extension context of the test method
     * @param owner the annotation of the extension that reads the argument sets, used in the error message
     * @throws ExtensionConfigurationException if the test method is annotated with {@link ParameterizedTest}
     */
    static void rejectParameterizedTest(
            @NonNull final ExtensionContext context, @NonNull final Class<? extends Annotation> owner) {
        final Method testMethod = context.getRequiredTestMethod();
        if (AnnotationSupport.isAnnotated(testMethod, ParameterizedTest.class)) {
            throw configurationError(
                    testMethod,
                    ("must not combine @%s with @%s: @%s reads the argument source itself, and JUnit would run the "
                                    + "@%s invocations in addition. Remove @%s.")
                            .formatted(
                                    owner.getSimpleName(),
                                    ParameterizedTest.class.getSimpleName(),
                                    owner.getSimpleName(),
                                    ParameterizedTest.class.getSimpleName(),
                                    ParameterizedTest.class.getSimpleName()));
        }
    }

    /**
     * Checks whether the given annotation declares an argument source that JUnit would recognize.
     */
    private static boolean isArgumentsSource(@NonNull final Annotation annotation) {
        return annotation instanceof ArgumentsSource
                || annotation instanceof ArgumentsSources
                || AnnotationSupport.isAnnotated(annotation.annotationType(), ArgumentsSource.class);
    }

    /**
     * Adds the argument sets returned by the factory methods of a {@link MethodSource}.
     */
    private static void readMethodSource(
            @NonNull final ExtensionContext context,
            @NonNull final MethodSource methodSource,
            @NonNull final List<ArgumentSet> argumentSets) {
        final Method testMethod = context.getRequiredTestMethod();
        final String[] factoryNames = methodSource.value().length == 0 ? new String[] {""} : methodSource.value();
        for (final String factoryName : factoryNames) {
            final String name = factoryName.isBlank() ? testMethod.getName() : factoryName.trim();
            if (name.contains("#") || name.contains("(")) {
                throw configurationError(
                        testMethod,
                        ("references the factory method '%s', but only factory methods without parameters in the "
                                        + "test class are supported")
                                .formatted(name));
            }
            final Method factory = ReflectionSupport.findMethod(context.getRequiredTestClass(), name)
                    .orElseThrow(() -> configurationError(
                            testMethod, "references the factory method '%s', which does not exist".formatted(name)));
            if (!Modifier.isStatic(factory.getModifiers())) {
                throw configurationError(
                        testMethod, "references the factory method '%s', which is not static".formatted(name));
            }
            final Object result = ReflectionSupport.invokeMethod(factory, null);
            for (final Object element : elementsOf(testMethod, name, result)) {
                argumentSets.add(toArgumentSet(element));
            }
        }
    }

    /**
     * Returns the elements of the value returned by a factory method.
     */
    @NonNull
    private static List<Object> elementsOf(
            @NonNull final Method testMethod, @NonNull final String factoryName, @Nullable final Object result) {
        return switch (result) {
            case final BaseStream<?, ?> stream -> {
                try (stream) {
                    yield toList(stream.iterator());
                }
            }
            case final Iterable<?> iterable -> toList(iterable.iterator());
            case final Iterator<?> iterator -> toList(iterator);
            case final Object array when array.getClass().isArray() -> arrayElements(array);
            case null, default ->
                throw configurationError(
                        testMethod,
                        ("references the factory method '%s', which must return a Stream, an Iterable, an Iterator "
                                        + "or an array")
                                .formatted(factoryName));
        };
    }

    /**
     * Converts an element returned by a factory method into an argument set, following the rules of JUnit's
     * {@link MethodSource}.
     */
    @NonNull
    private static ArgumentSet toArgumentSet(@Nullable final Object element) {
        return switch (element) {
            case final Arguments arguments -> ArgumentSet.of(arguments.get());
            case final Object[] values -> ArgumentSet.of(values);
            case null, default -> ArgumentSet.of(element);
        };
    }

    /**
     * Adds an argument set with a single value for every element of a {@link ValueSource}.
     */
    private static void readValueSource(
            @NonNull final Method testMethod,
            @NonNull final ValueSource valueSource,
            @NonNull final List<ArgumentSet> argumentSets) {
        final List<Object> attributes = Stream.<Object>of(
                        valueSource.shorts(),
                        valueSource.bytes(),
                        valueSource.ints(),
                        valueSource.longs(),
                        valueSource.floats(),
                        valueSource.doubles(),
                        valueSource.chars(),
                        valueSource.booleans(),
                        valueSource.strings(),
                        valueSource.classes())
                .filter(array -> Array.getLength(array) > 0)
                .toList();
        if (attributes.size() != 1) {
            throw configurationError(
                    testMethod,
                    "must set exactly one attribute of @%s, but %d are set"
                            .formatted(ValueSource.class.getSimpleName(), attributes.size()));
        }
        for (final Object value : arrayElements(attributes.getFirst())) {
            argumentSets.add(ArgumentSet.of(value));
        }
    }

    /**
     * Checks that the values of an argument set can be bound to the leading parameters of the test method.
     */
    private static void validate(
            @NonNull final Method testMethod, final int setNumber, @NonNull final ArgumentSet argumentSet) {
        final Class<?>[] parameterTypes = testMethod.getParameterTypes();
        final List<Object> values = argumentSet.values();
        if (values.size() > parameterTypes.length) {
            throw configurationError(
                    testMethod,
                    "receives %d values in argument set %d [%s], but declares only %d parameters"
                            .formatted(values.size(), setNumber, argumentSet.label(), parameterTypes.length));
        }
        for (int i = 0; i < values.size(); i++) {
            final Class<?> parameterType = parameterTypes[i];
            if (TestEnvironment.class.equals(parameterType)) {
                throw configurationError(
                        testMethod,
                        ("binds argument set %d [%s] to its %s parameter; declare the %s parameter after the "
                                        + "parameters that receive the arguments")
                                .formatted(
                                        setNumber,
                                        argumentSet.label(),
                                        TestEnvironment.class.getSimpleName(),
                                        TestEnvironment.class.getSimpleName()));
            }
            final Object value = values.get(i);
            if (!isAssignable(value, parameterType)) {
                throw configurationError(
                        testMethod,
                        "cannot bind value %d of argument set %d [%s] of type %s to a parameter of type %s"
                                .formatted(
                                        i + 1,
                                        setNumber,
                                        argumentSet.label(),
                                        value == null
                                                ? "null"
                                                : value.getClass().getName(),
                                        parameterType.getName()));
            }
        }
    }

    /**
     * Checks whether the given value can be passed to a parameter of the given type without conversion.
     */
    private static boolean isAssignable(@Nullable final Object value, @NonNull final Class<?> parameterType) {
        if (value == null) {
            return !parameterType.isPrimitive();
        }
        final Class<?> boxedType = MethodType.methodType(parameterType).wrap().returnType();
        return boxedType.isInstance(value);
    }

    @NonNull
    private static List<Object> toList(@NonNull final Iterator<?> iterator) {
        final List<Object> elements = new ArrayList<>();
        iterator.forEachRemaining(elements::add);
        return elements;
    }

    @NonNull
    private static List<Object> arrayElements(@NonNull final Object array) {
        return IntStream.range(0, Array.getLength(array))
                .mapToObj(i -> Array.get(array, i))
                .toList();
    }

    @NonNull
    private static ExtensionConfigurationException configurationError(
            @NonNull final Method testMethod, @NonNull final String problem) {
        return new ExtensionConfigurationException("Test method %s.%s() %s"
                .formatted(testMethod.getDeclaringClass().getSimpleName(), testMethod.getName(), problem));
    }
}
