// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.assertions.matchers;

import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.testcontainers.shaded.org.hamcrest.Description;
import org.testcontainers.shaded.org.hamcrest.Matcher;
import org.testcontainers.shaded.org.hamcrest.Matchers;
import org.testcontainers.shaded.org.hamcrest.TypeSafeDiagnosingMatcher;

/**
 * Used in assertions to check if two objects are equal on a field-by-field basis.
 * Provides utilities to ignore fields and use custom matchers for certain fields.
 * Object comparison is done using reflections on all properties
 * which have a getter method and are not in the {@code ignoredFields} list.
 *
 * @author vyanev
 *
 * @param <T> the type of the object
 */
public class FieldByFieldMatcher<T> extends TypeSafeDiagnosingMatcher<T> {

    private static final Logger log = LogManager.getLogger(FieldByFieldMatcher.class);

    /**
     * The expected object
     */
    private final T expected;

    /**
     * The expected object's class
     */
    private final Class<?> expectedType;

    /**
     * The class to stop at when comparing fields
     */
    private final Class<?> stopClass;

    /**
     * The default matcher used if there is no custom matcher for that field
     */
    private final Function<Object, Matcher<Object>> defaultMatcher = Matchers::equalTo;

    /**
     * The custom matchers used for the specified fields
     */
    private final Map<String, Matcher<?>> customMatchers = new ConcurrentHashMap<>();

    /**
     * The specified fields to ignore when comparing the objects
     */
    private final Set<String> ignoredFields = new HashSet<>();

    /**
     * @param expected the expected object
     * @param stopClass the class to stop at when comparing fields
     */
    public FieldByFieldMatcher(final T expected, final Class<?> stopClass) {
        super(expected.getClass());
        this.expected = expected;
        this.expectedType = expected.getClass();
        this.stopClass = stopClass;
    }

    /**
     * @param matchers the custom matchers for the fields
     * @return this {@link FieldByFieldMatcher}
     */
    public FieldByFieldMatcher<T> withCustomMatchersForFields(final Map<String, Matcher<?>> matchers) {
        customMatchers.putAll(matchers);
        return this;
    }

    /**
     * @param fields the fields to ignore
     * @return this {@link FieldByFieldMatcher}
     */
    public FieldByFieldMatcher<T> ignoringFields(final String... fields) {
        ignoredFields.addAll(Arrays.asList(fields));
        return this;
    }

    /**
     * @param actual the actual object
     * @param mismatch {@link Description} of the mismatch
     * @return {@code true} if the actual object matches the expected object
     */
    @Override
    protected boolean matchesSafely(final T actual, final Description mismatch) {
        return matchesFieldByField(actual, mismatch);
    }

    /**
     * @param description {@link Description} of the expected object
     */
    @Override
    public void describeTo(final Description description) {
        description.appendText(expected.toString());
    }

    private boolean matchesFieldByField(final Object actual, final Description mismatch) {
        if (expected == actual) {
            return true;
        }

        if (expected == null || actual == null) {
            describeMismatch("****** Actual ******: %s%n".formatted(actual), mismatch);
            return false;
        }

        if (expectedType.isAssignableFrom(actual.getClass())) {
            try {
                final List<PropertyDescriptor> beanProperties = beanGetterProperties(actual, stopClass);
                for (final PropertyDescriptor beanProperty : beanProperties) {
                    final String fieldName = beanProperty.getName();
                    final Method readMethod = beanProperty.getReadMethod();
                    final Object expectedValue = readMethod.invoke(expected);
                    final Object actualValue = readMethod.invoke(actual);
                    if (!haveEqualFieldValue(expectedValue, actualValue, fieldName)) {
                        describeMismatch(
                                """
                                    ****** Mismatch in field '%s' ******
                                    ****** Expected ***: %s
                                    ****** Actual *****: %s
                                    """
                                        .formatted(fieldName, expectedValue, actualValue),
                                mismatch);
                        return false;
                    }
                }
                return true;
            } catch (IntrospectionException | InvocationTargetException | IllegalAccessException e) {
                log.error(e.getMessage(), e);
                return false;
            }
        }

        if (!defaultMatcher.apply(expected).matches(actual)) {
            describeMismatch("****** Actual ******: %s%n".formatted(actual), mismatch);
            return false;
        }

        return true;
    }

    private boolean haveEqualFieldValue(final Object expected, final Object actual, final String fieldName) {
        if (ignoredFields.contains(fieldName)) {
            return true;
        } else if (customMatchers.containsKey(fieldName)) {
            return customMatchers.get(fieldName).matches(actual);
        } else {
            return defaultMatcher.apply(expected).matches(actual);
        }
    }

    private void describeMismatch(final String message, final Description mismatch) {
        mismatch.appendText(message).appendText("\n");
        log.trace(message);
    }

    private static List<PropertyDescriptor> beanGetterProperties(final Object bean, final Class<?> stopClass)
            throws IntrospectionException {
        return Arrays.stream(
                        Introspector.getBeanInfo(bean.getClass(), stopClass).getPropertyDescriptors())
                .filter(descriptor -> Objects.nonNull(descriptor.getReadMethod()))
                .toList();
    }
}
