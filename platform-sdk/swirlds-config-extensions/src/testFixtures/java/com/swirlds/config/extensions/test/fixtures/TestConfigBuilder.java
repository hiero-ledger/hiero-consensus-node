// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.extensions.test.fixtures;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.config.api.converter.ConfigConverter;
import com.swirlds.config.api.source.ConfigSource;
import com.swirlds.config.api.validation.ConfigValidator;
import com.swirlds.config.extensions.sources.SimpleConfigSource;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import java.util.Objects;
import org.hiero.base.concurrent.locks.AutoClosableLock;
import org.hiero.base.concurrent.locks.Locks;
import org.hiero.base.concurrent.locks.locked.Locked;

/**
 * Helper for use the config in test and change the config for specific tests. Instance can be used per class or per
 * test.
 */
public class TestConfigBuilder {

    private final AutoClosableLock configLock = Locks.createAutoLock();

    private Configuration configuration = null;

    private final ConfigurationBuilder builder;

    /**
     * Creates a new instance and automatically registers all config data records (see {@link ConfigData}) on classpath
     * / modulepath that are part of the packages {@code com.swirlds} and {@code com.hedera}.
     */
    public TestConfigBuilder() {
        this(true);
    }

    /**
     * Creates a new instance and automatically registers all given config data records. This call will not do a class
     * scan for config data records on classpath / modulepath like some of the other constructors do.
     *
     * @param dataTypes
     */
    public TestConfigBuilder(@Nullable final Class<? extends Record> dataTypes) {
        this(false);
        if (dataTypes != null) {
            this.builder.withConfigDataTypes(dataTypes);
        }
    }

    /**
     * Creates a new instance and automatically registers all config data records (see {@link ConfigData}) on classpath
     * / modulepath that are part of the packages {@code com.swirlds} and {@code com.hedera}.
     * if the {@code registerAllTypes} param is true.
     *
     * @param registerAllTypes if true all config data records on classpath will automatically be registered
     */
    public TestConfigBuilder(final boolean registerAllTypes) {
        if (registerAllTypes) {
            this.builder = ConfigurationBuilder.create().autoDiscoverExtensions();
        } else {
            this.builder = ConfigurationBuilder.create();
        }
    }

    /**
     * Sets the value for the config.
     *
     * @param propertyName name of the property
     * @param values       list of values
     * @return the {@link TestConfigBuilder} instance (for fluent API)
     */
    @NonNull
    public TestConfigBuilder withValues(@NonNull final String propertyName, @Nullable final List<?> values) {
        return withSource(new SimpleConfigSource(propertyName, values));
    }

    /**
     * Sets the value for the config.
     *
     * @param propertyName name of the property
     * @param value        the value
     * @return the {@link TestConfigBuilder} instance (for fluent API)
     */
    @NonNull
    public TestConfigBuilder withValue(@NonNull final String propertyName, @Nullable final String value) {
        return withSource(new SimpleConfigSource(propertyName, value));
    }

    /**
     * Sets the value for the config.
     *
     * @param propertyName name of the property
     * @param value        the value
     * @return the {@link TestConfigBuilder} instance (for fluent API)
     */
    @NonNull
    public TestConfigBuilder withValue(@NonNull final String propertyName, final int value) {
        return withSource(new SimpleConfigSource(propertyName, value));
    }

    /**
     * Sets the value for the config.
     *
     * @param propertyName name of the property
     * @param value        the value
     * @return the {@link TestConfigBuilder} instance (for fluent API)
     */
    @NonNull
    public TestConfigBuilder withValue(@NonNull final String propertyName, final double value) {
        return withSource(new SimpleConfigSource(propertyName, value));
    }

    /**
     * Sets the value for the config.
     *
     * @param propertyName name of the property
     * @param value        the value
     * @return the {@link TestConfigBuilder} instance (for fluent API)
     */
    @NonNull
    public TestConfigBuilder withValue(@NonNull final String propertyName, final long value) {
        return withSource(new SimpleConfigSource(propertyName, value));
    }

    /**
     * Sets the value for the config.
     *
     * @param propertyName name of the property
     * @param value        the value
     * @return the {@link TestConfigBuilder} instance (for fluent API)
     */
    @NonNull
    public TestConfigBuilder withValue(@NonNull final String propertyName, final boolean value) {
        return withSource(new SimpleConfigSource(propertyName, value));
    }

    /**
     * Sets the value for the config.
     *
     * @param propertyName name of the property
     * @param value        the value
     * @return the {@link TestConfigBuilder} instance (for fluent API)
     */
    @NonNull
    public TestConfigBuilder withValue(@NonNull final String propertyName, @NonNull final Object value) {
        Objects.requireNonNull(value, "value must not be null");
        return withSource(new SimpleConfigSource(propertyName, value.toString()));
    }

    /**
     * This method returns the {@link Configuration} instance. If the method is called for the first time the
     * {@link Configuration} instance will be created. All values that have been set (see
     * {@link #withValue(String, int)}) methods will be part of the config. Next to this the config will support all
     * config data record types (see {@link ConfigData}) that are on the classpath.
     *
     * @return the created configuration
     */
    @NonNull
    public Configuration getOrCreateConfig() {
        try (final Locked ignore = configLock.lock()) {
            if (configuration == null) {
                configuration = builder.build();
            }
            return configuration;
        }
    }

    private void checkConfigState() {
        try (final Locked ignore = configLock.lock()) {
            if (configuration != null) {
                throw new IllegalStateException("Configuration already created!");
            }
        }
    }

    /**
     * Adds the given config source to the builder
     *
     * @param configSource the config source that will be added
     * @return the {@link TestConfigBuilder} instance (for fluent API)
     */
    @NonNull
    public TestConfigBuilder withSource(@NonNull final ConfigSource configSource) {
        checkConfigState();
        builder.withSource(configSource);
        return this;
    }

    /**
     * Adds the given config converter to the builder
     *
     * @param converterType the type of the config converter
     * @param converter the config converter that will be added
     * @return the {@link TestConfigBuilder} instance (for fluent API)
     */
    @NonNull
    public <T> TestConfigBuilder withConverter(
            @NonNull final Class<T> converterType, @NonNull final ConfigConverter<T> converter) {
        checkConfigState();
        builder.withConverter(converterType, converter);
        return this;
    }

    /**
     * Adds the given config validator to the builder
     *
     * @param validator the config validator that will be added
     * @return the {@link TestConfigBuilder} instance (for fluent API)
     */
    @NonNull
    public TestConfigBuilder withValidator(@NonNull final ConfigValidator validator) {
        checkConfigState();
        builder.withValidator(validator);
        return this;
    }

    /**
     * Adds the given config data type to the builder
     *
     * @param type the config data type that will be added
     * @param <T>  the type of the config data type
     * @return the {@link TestConfigBuilder} instance (for fluent API)
     */
    @NonNull
    public <T extends Record> TestConfigBuilder withConfigDataType(@NonNull final Class<T> type) {
        checkConfigState();
        builder.withConfigDataType(type);
        return this;
    }
}
