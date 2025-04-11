// SPDX-License-Identifier: Apache-2.0
package org.hiero.junit.extensions;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * {@literal @}ExtendWith(CombinatorialParameterExtension.class)
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
 *
 * Each source method must be static, take no parameters, and return a {@code Stream<?>}.
 */
public class CombinatorialParameterExtension implements TestTemplateInvocationContextProvider {

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
        Method testMethod = context.getRequiredTestMethod();
        UseParameterSources useSources = testMethod.getAnnotation(UseParameterSources.class);

        Map<String, List<Object>> valuesByParam = new LinkedHashMap<>();

        for (ParamSource source : useSources.value()) {
            List<Object> values = invokeSourceMethod(context, source.method());
            valuesByParam.put(source.param(), values);
        }

        List<String> paramNames = getParameterNames(testMethod);
        List<List<Object>> valueLists =
                paramNames.stream().map(valuesByParam::get).toList();

        // Cartesian product
        List<List<Object>> combinations = cartesianProduct(valueLists);

        return combinations.stream().map(combo -> new CombinatorialInvocationContext(paramNames, combo));
    }

    @SuppressWarnings("unchecked")
    private List<Object> invokeSourceMethod(final @NonNull ExtensionContext context, final @NonNull String methodName) {
        Method testMethod = context.getRequiredTestMethod();
        try {
            Method source = testMethod.getDeclaringClass().getDeclaredMethod(methodName);
            source.setAccessible(true);
            Object result = source.invoke(null);
            if (!(result instanceof Stream stream)) {
                throw new IllegalArgumentException("Source method must return Stream");
            }
            return stream.toList();
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke source method: " + methodName, e);
        }
    }

    private List<String> getParameterNames(final @NonNull Method testMethod) {
        return Arrays.stream(testMethod.getParameters())
                .map(p -> {
                    ParamName named = p.getAnnotation(ParamName.class);
                    if (named == null) {
                        throw new RuntimeException("All parameters must be annotated with @Named");
                    }
                    return named.value();
                })
                .toList();
    }

    private List<List<Object>> cartesianProduct(final @NonNull List<List<Object>> lists) {
        List<List<Object>> result = new ArrayList<>();
        cartesianHelper(lists, result, new ArrayList<>(), 0);
        return result;
    }

    private void cartesianHelper(
            final @NonNull List<List<Object>> lists,
            final @NonNull List<List<Object>> result,
            final @NonNull List<Object> current,
            int depth) {
        if (depth == lists.size()) {
            result.add(new ArrayList<>(current));
            return;
        }
        for (Object o : lists.get(depth)) {
            current.add(o);
            cartesianHelper(lists, result, current, depth + 1);
            current.remove(current.size() - 1);
        }
    }

    static class CombinatorialInvocationContext implements TestTemplateInvocationContext {
        private final List<String> paramNames;
        private final List<Object> values;

        CombinatorialInvocationContext(final @NonNull List<String> paramNames, final @NonNull List<Object> values) {
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
