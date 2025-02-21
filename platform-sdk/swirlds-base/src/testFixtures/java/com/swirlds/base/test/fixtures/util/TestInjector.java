// SPDX-License-Identifier: Apache-2.0
package com.swirlds.base.test.fixtures.util;

import edu.umd.cs.findbugs.annotations.NonNull;
import jakarta.inject.Inject;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;

/**
 * This class is used to inject a dependency into a test. It should be used by JUnit extensions.
 */
public class TestInjector {

    /**
     * This class should not be instantiated.
     */
    private TestInjector() {}

    /**
     * Injects the result of the given supplier into the test instance. The result of the supplier is injected into any
     * field of the test instance that is annotated with {@link Inject} and is of the given type.
     *
     * @param type             the type of the dependency to inject
     * @param supplier         the supplier that provides the dependency
     * @param extensionContext the extension context of the test
     * @param <T>              the type of the dependency to inject
     */
    public static <T> void injectInTest(Class<T> type, Supplier<T> supplier, ExtensionContext extensionContext) {
        final Class<?> testClass = extensionContext.getRequiredTestClass();
        Arrays.stream(testClass.getDeclaredFields())
                .filter(field -> !Modifier.isFinal(field.getModifiers()))
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .filter(field -> field.isAnnotationPresent(Inject.class))
                .filter(field -> Objects.equals(field.getType(), type))
                .forEach(field -> {
                    try {
                        field.setAccessible(true);
                        field.set(extensionContext.getRequiredTestInstance(), supplier.get());
                    } catch (Exception ex) {
                        throw new RuntimeException("Error in injection", ex);
                    }
                });
    }

    /**
     * Checks if the given parameter context supports a parameter of the specified class type.
     *
     * @param parameterContext The parameter context to check for support.
     * @param cls              The class type to check support for.
     * @param <T>              The type of the class.
     * @return True if the parameter context supports the specified class type, false otherwise.
     * @throws NullPointerException If either parameterContext or cls is null.
     */
    public static <T> boolean supportsParameter(
            final @NonNull ParameterContext parameterContext, final @NonNull Class<T> cls) {
        Objects.requireNonNull(parameterContext, "parameterContext must not be null");
        Objects.requireNonNull(cls, "cls must not be null");

        return Optional.of(parameterContext)
                .map(ParameterContext::getParameter)
                .map(Parameter::getType)
                .filter(cls::isAssignableFrom)
                .isPresent();
    }

    /**
     * Resolves a parameter from the given parameter context using the provided instance supplier.
     *
     * @param parameterContext   The parameter context to resolve the parameter from.
     * @param instanceSupplier   The supplier that provides an instance of the parameter type.
     * @param <T>                The type of the parameter to resolve.
     * @return The resolved parameter.
     * @throws NullPointerException       If either parameterContext or instanceSupplier is null.
     * @throws ParameterResolutionException If the parameter cannot be resolved.
     */
    public static <T> T resolveParameter(
            @NonNull final Class<T> type,
            @NonNull final ParameterContext parameterContext,
            final @NonNull Supplier<T> instanceSupplier) {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(parameterContext, "parameterContext must not be null");
        Objects.requireNonNull(instanceSupplier, "instanceSupplier must not be null");

        return Optional.of(parameterContext)
                .map(ParameterContext::getParameter)
                .map(Parameter::getType)
                .filter(t -> t.equals(type))
                .map(t -> instanceSupplier.get())
                .orElseThrow(() -> new ParameterResolutionException("Could not resolve parameter"));
    }
}
