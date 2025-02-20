// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.extensions.sources;

import static com.swirlds.config.extensions.sources.ConfigSourceOrdinalConstants.PROGRAMMATIC_VALUES_ORDINAL;

import com.swirlds.base.ArgumentUtils;
import com.swirlds.config.api.source.ConfigSource;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A {@link ConfigSource} implementation that can be used to provide values of properties
 * programmatically by calling {@link #withValue(String, Long)} (String, String)}.
 */
public final class SimpleConfigSource implements ConfigSource {

    private final Map<String, String> internalProperties;
    private final Map<String, List<String>> internalListProperties;

    private int ordinal = PROGRAMMATIC_VALUES_ORDINAL;

    /**
     * Creates an instance without any config properties.
     */
    public SimpleConfigSource() {
        internalProperties = new HashMap<>();
        internalListProperties = new HashMap<>();
    }

    /**
     * Creates an instance with the given config properties and empty list properties.
     */
    public SimpleConfigSource(@NonNull final Map<String, String> properties) {
        this(properties, new HashMap<>());
    }

    /**
     * Creates an instance with the given config properties and list properties.
     */
    public SimpleConfigSource(
            @NonNull final Map<String, String> properties, @NonNull final Map<String, List<String>> listProperties) {
        // defensive copy
        internalProperties = new HashMap<>(Objects.requireNonNull(properties));
        internalListProperties = new HashMap<>(Objects.requireNonNull(listProperties));
    }

    /**
     * Creates an instance and directly adds the given config property.
     *
     * @param propertyName name of the config property
     * @param value        value of the config property
     */
    public SimpleConfigSource(final String propertyName, final String value) {
        this();
        withValue(propertyName, value);
    }

    public SimpleConfigSource(final String propertyName, final List<?> value) {
        this();
        setValues(propertyName, value, Object::toString);
    }

    /**
     * Creates an instance and directly adds the given config property.
     *
     * @param propertyName name of the config property
     * @param value        value of the config property
     */
    public SimpleConfigSource(final String propertyName, final Integer value) {
        this();
        withValue(propertyName, value);
    }

    /**
     * Creates an instance and directly adds the given config property.
     *
     * @param propertyName name of the config property
     * @param value        value of the config property
     */
    public SimpleConfigSource(final String propertyName, final Long value) {
        this();
        withValue(propertyName, value);
    }

    /**
     * Creates an instance and directly adds the given config property.
     *
     * @param propertyName name of the config property
     * @param value        value of the config property
     */
    public SimpleConfigSource(final String propertyName, final Double value) {
        this();
        withValue(propertyName, value);
    }

    /**
     * Creates an instance and directly adds the given config property.
     *
     * @param propertyName name of the config property
     * @param value        value of the config property
     */
    public SimpleConfigSource(final String propertyName, final Float value) {
        this();
        withValue(propertyName, value);
    }

    /**
     * Creates an instance and directly adds the given config property.
     *
     * @param propertyName name of the config property
     * @param value        value of the config property
     */
    public SimpleConfigSource(final String propertyName, final Boolean value) {
        this();
        withValue(propertyName, value);
    }

    /**
     * Adds a string value to this source.
     *
     * @param propertyName name of the property
     * @param value        default value
     */
    public SimpleConfigSource withValue(final String propertyName, final String value) {
        setValue(propertyName, value, v -> v);
        return this;
    }

    /**
     * Adds an int value to this source.
     *
     * @param propertyName name of the property
     * @param value        default value
     */
    public SimpleConfigSource withValue(final String propertyName, final Integer value) {
        setValue(propertyName, value, v -> Integer.toString(v));
        return this;
    }

    /**
     * Adds a double value to this source.
     *
     * @param propertyName name of the property
     * @param value        default value
     */
    public SimpleConfigSource withValue(final String propertyName, final Double value) {
        setValue(propertyName, value, v -> Double.toString(v));
        return this;
    }

    /**
     * Adds a float value to this source.
     *
     * @param propertyName name of the property
     * @param value        default value
     */
    public SimpleConfigSource withValue(final String propertyName, final Float value) {
        setValue(propertyName, value, v -> Float.toString(v));
        return this;
    }

    /**
     * Adds a long value to this source.
     *
     * @param propertyName name of the property
     * @param value        default value
     */
    public SimpleConfigSource withValue(final String propertyName, final Long value) {
        setValue(propertyName, value, v -> Long.toString(v));
        return this;
    }

    /**
     * Adds a boolean value to this source.
     *
     * @param propertyName name of the property
     * @param value        default value
     */
    @NonNull
    public SimpleConfigSource withValue(@NonNull final String propertyName, @NonNull final Boolean value) {
        setValue(propertyName, value, v -> Boolean.toString(v));
        return this;
    }

    private <T> void setValue(
            @NonNull final String propertyName, @Nullable final T value, @NonNull Function<T, String> converter) {
        ArgumentUtils.throwArgBlank(propertyName, "propertyName");
        Objects.requireNonNull(converter, "converter must not be null");
        internalProperties.put(
                propertyName, Optional.ofNullable(value).map(converter::apply).orElse(null));
    }

    /**
     * Adds a list value to this source.
     *
     * @param propertyName name of the property
     * @param values       default values list
     */
    public SimpleConfigSource withBooleanValues(final String propertyName, final List<Boolean> values) {
        setValues(propertyName, values, v -> Boolean.toString(v));
        return this;
    }

    /**
     * Adds a list value to this source.
     *
     * @param propertyName name of the property
     * @param values       default values list
     */
    public SimpleConfigSource withIntegerValues(final String propertyName, final List<Integer> values) {
        setValues(propertyName, values, v -> Integer.toString(v));
        return this;
    }

    /**
     * Adds a list value to this source.
     *
     * @param propertyName name of the property
     * @param values       default values list
     */
    public SimpleConfigSource withLongValues(final String propertyName, final List<Long> values) {
        setValues(propertyName, values, v -> Long.toString(v));
        return this;
    }

    /**
     * Adds a list value to this source.
     *
     * @param propertyName name of the property
     * @param values       default values list
     */
    public SimpleConfigSource withDoubleValues(final String propertyName, final List<Double> values) {
        setValues(propertyName, values, v -> Double.toString(v));
        return this;
    }

    /**
     * Adds a list value to this source.
     *
     * @param propertyName name of the property
     * @param values       default values list
     */
    public SimpleConfigSource withStringValues(final String propertyName, final List<String> values) {
        setValues(propertyName, values, v -> v);
        return this;
    }

    private <T> void setValues(
            @NonNull final String propertyName,
            @Nullable final List<T> values,
            @NonNull Function<T, String> converter) {
        ArgumentUtils.throwArgBlank(propertyName, "propertyName");
        Objects.requireNonNull(converter, "converter must not be null");
        if (values == null) {
            internalListProperties.put(propertyName, null);
        } else if (values.isEmpty()) {
            internalListProperties.put(propertyName, List.of());
        } else {
            final List<String> rawValues = values.stream().map(converter).toList();
            internalListProperties.put(propertyName, rawValues);
        }
    }

    /**
     * Specify the ordinal of this source. Default is {@link ConfigSourceOrdinalConstants#PROGRAMMATIC_VALUES_ORDINAL}.
     *
     * @param ordinal the ordinal
     * @return this
     */
    public SimpleConfigSource withOrdinal(final int ordinal) {
        this.ordinal = ordinal;
        return this;
    }

    /**
     * Removes all default properties.
     */
    public void reset() {
        internalProperties.clear();
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public Set<String> getPropertyNames() {
        return Stream.concat(internalProperties.keySet().stream(), internalListProperties.keySet().stream())
                .collect(Collectors.toSet());
    }

    /**
     * {@inheritDoc}
     */
    @Nullable
    @Override
    public String getValue(@NonNull final String propertyName) throws NoSuchElementException {
        if (internalProperties.containsKey(propertyName)) {
            return internalProperties.get(propertyName);
        }
        throw new NoSuchElementException("Property " + propertyName + " not found");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isListProperty(@NonNull final String propertyName) throws NoSuchElementException {
        return internalListProperties.containsKey(propertyName);
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public List<String> getListValue(@NonNull final String propertyName) throws NoSuchElementException {
        if (internalListProperties.containsKey(propertyName)) {
            return internalListProperties.get(propertyName);
        }
        throw new NoSuchElementException("Property " + propertyName + " not found");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getOrdinal() {
        return ordinal;
    }
}
