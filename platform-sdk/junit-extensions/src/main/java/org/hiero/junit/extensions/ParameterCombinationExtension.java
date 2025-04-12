// SPDX-License-Identifier: Apache-2.0
package org.hiero.junit.extensions;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
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
 * Each source method must be static, take no parameters, and return a {@code Stream<?>}.
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
        for (String name : paramNames) {
            final ParamSource source = getParamSource(name, useSources, testMethod);
            valueLists.add(invokeSourceMethod(context, source.method()));
        }

        final List<List<Object>> combinations = com.google.common.collect.Lists.cartesianProduct(valueLists);

        return combinations.stream().map(combo -> new Context(paramNames, combo));
    }

    private static ParamSource getParamSource(
            final String name, final UseParameterSources useSources, final Method testMethod) {
        ParamSource source = null;
        for (ParamSource s : useSources.value()) {
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
    private List<Object> invokeSourceMethod(final @NonNull ExtensionContext context, final @NonNull String methodName) {
        final Method testMethod = context.getRequiredTestMethod();
        try {
            Method source = testMethod.getDeclaringClass().getDeclaredMethod(methodName);
            source.setAccessible(true);
            Object result = source.invoke(null);
            if (result instanceof Stream stream) {
                return stream.toList();
            } else if (result instanceof Collection collection) {
                return collection.stream().toList();
            } else if (result instanceof Iterable iterable) {
                final var list = new ArrayList<>();
                iterable.forEach(list::add);
                return List.copyOf(list);
            }
            throw new IllegalStateException("Source method must return Stream, Collection or Iterable");
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
                    ParamName annotation = p.getAnnotation(ParamName.class);
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
                    String paramName =
                            pc.getParameter().getAnnotation(ParamName.class).value();
                    int index = paramNames.indexOf(paramName);
                    return values.get(index);
                }
            });
        }
    }
}
