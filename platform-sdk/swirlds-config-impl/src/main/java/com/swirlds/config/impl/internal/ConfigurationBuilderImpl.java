// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.impl.internal;

import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.config.api.ConfigurationExtension;
import com.swirlds.config.api.ConfigurationExtension.ConverterPair;
import com.swirlds.config.api.converter.ConfigConverter;
import com.swirlds.config.api.source.ConfigSource;
import com.swirlds.config.api.validation.ConfigValidator;
import com.swirlds.config.extensions.sources.SimpleConfigSource;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.lang.reflect.ParameterizedType;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.ServiceLoader.Provider;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * This class is an implementation of the {@link ConfigurationBuilder} interface that is based on the given config
 * implementation.
 */
final class ConfigurationBuilderImpl implements ConfigurationBuilder {

    /**
     * Internal lock to support a thread safe initialization.
     */
    private final ReentrantLock initializationLock = new ReentrantLock();

    /**
     * The configuration instance.
     */
    private final ConfigurationImpl configuration;

    /**
     * The service that handles all {@link ConfigSource} instances.
     */
    private final ConfigSourceService configSourceService;

    /**
     * The service that handles all {@link ConfigConverter} instances.
     */
    private final ConverterService converterService;

    /**
     * The service that handles all {@link ConfigValidator} instances.
     */
    private final ConfigValidationService validationService;

    /**
     * The service that holds all properties of the configuration.
     */
    private final ConfigPropertiesService propertiesService;

    /**
     * initialization flag.
     */
    private final AtomicBoolean initialized = new AtomicBoolean();

    /**
     * Key-value pairs specified by {@link #withValue(String, String)}.
     */
    private final Map<String, String> properties = new HashMap<>();

    /**
     * Default constructor that creates all internal services.
     */
    ConfigurationBuilderImpl() {
        configSourceService = new ConfigSourceService();
        converterService = new ConverterService();
        propertiesService = new ConfigPropertiesService(configSourceService);
        validationService = new ConfigValidationService(converterService);
        configuration = new ConfigurationImpl(propertiesService, converterService, validationService);
    }

    @NonNull
    @Override
    public Configuration build() {
        initializationLock.lock();
        try {
            if (initialized.get()) {
                throw new IllegalStateException("Configuration already initialized");
            }
            if (!properties.isEmpty()) {
                withSource(new SimpleConfigSource(properties).withOrdinal(CUSTOM_PROPERTY_ORDINAL));
            }
            configSourceService.init();
            converterService.init();
            propertiesService.init();
            validationService.init();
            configuration.init();
            initialized.set(true);
        } finally {
            initializationLock.unlock();
        }
        return configuration;
    }

    @NonNull
    @Override
    public ConfigurationBuilder autoDiscoverExtensions() {
        final ServiceLoader<ConfigurationExtension> serviceLoader = ServiceLoader.load(ConfigurationExtension.class);
        final List<ConfigurationExtension> extensions =
                serviceLoader.stream().map(Provider::get).toList();

        for (final ConfigurationExtension extension : extensions) {
            loadExtension(extension);
        }

        return this;
    }

    @NonNull
    @Override
    public ConfigurationBuilder loadExtension(@NonNull final ConfigurationExtension extension) {
        extension.getConverters().forEach(this::addConverter);
        extension.getValidators().forEach(this::addValidator);
        extension.getConfigDataTypes().forEach(this::addConfigDataType);
        extension.getConfigSources().forEach(this::addConfigSource);
        return this;
    }

    private <T> void addConverter(final ConverterPair<T> converterPair) {
        addConverter(converterPair.type(), converterPair.converter());
    }

    @NonNull
    @Override
    public ConfigurationBuilder withSource(@NonNull final ConfigSource configSource) {
        addConfigSource(configSource);
        return this;
    }

    @NonNull
    @Override
    public ConfigurationBuilder withSources(@NonNull final ConfigSource... configSources) {
        Arrays.stream(configSources).forEach(this::addConfigSource);
        return this;
    }

    private void addConfigSource(@NonNull final ConfigSource configSource) {
        Objects.requireNonNull(configSource, "configSource must not be null");
        if (initialized.get()) {
            throw new IllegalStateException("ConfigSource can not be added to initialized config");
        }
        configSourceService.addConfigSource(configSource);
    }

    /**
     * @param converter the converter that should be used for the configuration
     * @deprecated Use {@link ConfigurationBuilderImpl#withConverter(Class, ConfigConverter)}
     */
    @NonNull
    @Override
    @SuppressWarnings("unchecked")
    @Deprecated(forRemoval = true)
    public ConfigurationBuilder withConverter(@NonNull final ConfigConverter<?> converter) {
        addConverter(getConverterType(converter.getClass()), converter);
        return this;
    }

    @NonNull
    @Override
    public <T> ConfigurationBuilder withConverter(
            @NonNull final Class<T> converterType, @NonNull final ConfigConverter<T> converter) {
        addConverter(converterType, converter);
        return this;
    }

    /**
     * @param converters the converters that should be used for the configuration
     * @deprecated Use {@link ConfigurationBuilderImpl#withConverter(Class, ConfigConverter)}
     */
    @NonNull
    @Override
    @Deprecated(forRemoval = true)
    public ConfigurationBuilder withConverters(@NonNull final ConfigConverter<?>... converters) {
        Arrays.stream(converters).forEach(this::withConverter);
        return this;
    }

    private <T> void addConverter(@NonNull final Class<T> converterType, @NonNull final ConfigConverter<T> converter) {
        Objects.requireNonNull(converter, "converter must not be null");
        if (initialized.get()) {
            throw new IllegalStateException("Converters can not be added to initialized config");
        }
        converterService.addConverter(converterType, converter);
    }

    @NonNull
    @Override
    public ConfigurationBuilder withValidator(@NonNull final ConfigValidator validator) {
        addValidator(validator);
        return this;
    }

    @NonNull
    @Override
    public ConfigurationBuilder withValidators(@NonNull final ConfigValidator... validators) {
        Arrays.stream(validators).forEach(this::addValidator);
        return this;
    }

    private void addValidator(@NonNull final ConfigValidator validator) {
        Objects.requireNonNull(validator, "validator must not be null");
        if (initialized.get()) {
            throw new IllegalStateException("ConfigValidator can not be added to initialized config");
        }
        validationService.addValidator(validator);
    }

    @NonNull
    @Override
    public <T extends Record> ConfigurationBuilder withConfigDataType(@NonNull final Class<T> type) {
        addConfigDataType(type);
        return this;
    }

    @NonNull
    @Override
    public ConfigurationBuilder withConfigDataTypes(@NonNull final Class<? extends Record>... types) {
        Arrays.stream(types).forEach(this::addConfigDataType);
        return this;
    }

    private <T extends Record> void addConfigDataType(@NonNull final Class<T> type) {
        Objects.requireNonNull(type, "type must not be null");
        if (initialized.get()) {
            throw new IllegalStateException("ConfigDataType can not be added to initialized config");
        }
        configuration.addConfigDataType(type);
    }

    /**
     * Sets the value for the config.
     *
     * @param propertyName name of the property
     * @param value        the value
     * @return the {@link ConfigurationBuilder} instance (for fluent API)
     */
    @NonNull
    public ConfigurationBuilder withValue(@NonNull final String propertyName, @NonNull final String value) {
        Objects.requireNonNull(propertyName, "propertyName must not be null");
        Objects.requireNonNull(value, "value must not be null");
        properties.put(propertyName, value);
        return this;
    }

    @NonNull
    @SuppressWarnings("unchecked")
    private static <T, C extends ConfigConverter<T>> Class<T> getConverterType(@NonNull final Class<C> converterClass) {
        Objects.requireNonNull(converterClass, "converterClass must not be null");
        return Arrays.stream(converterClass.getGenericInterfaces())
                .filter(ParameterizedType.class::isInstance)
                .map(ParameterizedType.class::cast)
                .filter(parameterizedType -> Objects.equals(ConfigConverter.class, parameterizedType.getRawType()))
                .map(ParameterizedType::getActualTypeArguments)
                .findAny()
                .map(typeArguments -> {
                    if (typeArguments.length != 1) {
                        throw new IllegalStateException("Can not extract generic type for converter " + converterClass);
                    }
                    return (Class<T>) typeArguments[0];
                })
                .orElseGet(() -> getConverterType((Class<C>) converterClass.getSuperclass()));
    }
}
