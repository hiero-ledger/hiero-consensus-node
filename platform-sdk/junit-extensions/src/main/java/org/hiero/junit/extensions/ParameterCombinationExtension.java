// SPDX-License-Identifier: Apache-2.0
package org.hiero.junit.extensions;

import com.google.common.base.Strings;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.extension.Extension;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.jupiter.api.extension.TestTemplateInvocationContext;
import org.junit.jupiter.api.extension.TestTemplateInvocationContextProvider;

/**
 * A template executor that allows to individually indicate source methods for each parameter in the test method.
 * <p><b>Example:</b>
 * <pre><code>
 * {@literal @}TestTemplate
 * {@literal @}ExtendWith(ParameterCombinationExtension.class)
 * {@literal @}UseParameterSources({
 *     {@literal @}ParamSource(param = "username", method = "usernameSource"),
 *     {@literal @}ParamSource(param = "age", method = "ageSource")
 * })
 * void testUser({@literal @}ParamName("username") String username, {@literal @}ParamName("age") int age) {
 *     // This method will be executed for all combinations of usernames and ages.
 * }
 * </code></pre>
 * This extension works in conjunction with the {@link UseParameterSources} and
 * {@link ParamSource} annotations and requires test parameters to be annotated
 * with {@link ParamName}.
 * <p>
 * Each source method must be static and take no parameters.
 */
public class ParameterCombinationExtension implements TestTemplateInvocationContextProvider {

    @Override
    public boolean supportsTestTemplate(final @NonNull ExtensionContext context) {
        return context.getRequiredTestMethod().isAnnotationPresent(UseParameterSources.class)
                && context.getTestMethod()
                        .map(m -> Arrays.stream(m.getParameters()))
                        .orElse(Stream.empty())
                        .anyMatch(p -> p.isAnnotationPresent(ParamName.class));
    }

    @Override
    public Stream<TestTemplateInvocationContext> provideTestTemplateInvocationContexts(
            final @NonNull ExtensionContext context) {
        final Method testMethod = context.getRequiredTestMethod();
        final UseParameterSources useSources = testMethod.getAnnotation(UseParameterSources.class);

        final List<String> paramNames = getParameterNames(testMethod);
        final List<List<Object>> valueLists = new ArrayList<>();
        for (final String name : paramNames) {
            final ParamSource source = getParamSource(name, useSources, testMethod);
            valueLists.add(invokeSourceMethod(context, source.fullyQualifiedClass(), source.method()));
        }

        final List<List<Object>> combinations = com.google.common.collect.Lists.cartesianProduct(valueLists);

        return combinations.stream().map(combo -> new Context(paramNames, combo));
    }

    private static ParamSource getParamSource(
            final String name, final UseParameterSources useSources, final Method testMethod) {
        ParamSource source = null;
        for (final ParamSource s : useSources.value()) {
            if (name.equals(s.param())) {
                source = s;
                break;
            }
        }
        if (source == null) {
            throw new IllegalStateException(
                    ParamName.class.getSimpleName() + ":" + name + " could not be found in any: "
                            + ParamSource.class.getSimpleName() + " for " + testMethod.getName());
        }
        return source;
    }

    @SuppressWarnings("unchecked")
    private List<Object> invokeSourceMethod(
            final @NonNull ExtensionContext context,
            final @NonNull String className,
            final @NonNull String methodName) {
        final Method testMethod = context.getRequiredTestMethod();
        try {
            final Class<?> theClazz =
                    (Strings.isNullOrEmpty(className)) ? testMethod.getDeclaringClass() : Class.forName(className);
            final Method source = theClazz.getDeclaredMethod(methodName);
            final int staticModifiers = source.getModifiers();
            final boolean isStatic = Modifier.isStatic(staticModifiers);
            if (!isStatic) {
                throw new IllegalStateException("Source method: " + className + "#" + methodName + " must be static");
            }

            source.setAccessible(true);
            final Object result = source.invoke(null);
            if (result instanceof final Stream stream) {
                return stream.toList();
            } else if (result instanceof final Collection collection) {
                return collection.stream().toList();
            } else if (result instanceof final Iterable iterable) {
                return StreamSupport.stream(iterable.spliterator(), false).toList();
            }
            if (result instanceof final Object[] array) {
                return Arrays.stream(array).toList();
            } else {
                return List.of(result);
            }
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(
                    "method: " + methodName + " does not exist on " + context.getTestClass(), e);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to invoke source method: " + methodName, e);
        }
    }

    @NonNull
    private List<String> getParameterNames(final @NonNull Method testMethod) {
        return Arrays.stream(testMethod.getParameters())
                .map(p -> {
                    final ParamName annotation = p.getAnnotation(ParamName.class);
                    if (annotation == null) {
                        throw new RuntimeException("All parameters must be annotated with" + ParamName.class.getName());
                    }
                    return annotation.value();
                })
                .toList();
    }

    static class Context implements TestTemplateInvocationContext {
        private final List<String> paramNames;
        private final List<Object> values;

        Context(final @NonNull List<String> paramNames, final @NonNull List<Object> values) {
            this.paramNames = paramNames;
            this.values = values;
        }

        @Override
        public String getDisplayName(int invocationIndex) {
            return paramNames.stream()
                    .map(name -> name + "=" + values.get(paramNames.indexOf(name)))
                    .collect(Collectors.joining(", "));
        }

        @Override
        public List<Extension> getAdditionalExtensions() {
            return List.of(new ParameterResolver() {
                @Override
                public boolean supportsParameter(ParameterContext pc, ExtensionContext ec) {
                    return pc.getParameter().isAnnotationPresent(ParamName.class);
                }

                @Override
                public Object resolveParameter(final @NonNull ParameterContext pc, final @NonNull ExtensionContext ec) {
                    final String paramName =
                            pc.getParameter().getAnnotation(ParamName.class).value();
                    final int index = paramNames.indexOf(paramName);
                    return values.get(index);
                }
            });
        }
    }
}
