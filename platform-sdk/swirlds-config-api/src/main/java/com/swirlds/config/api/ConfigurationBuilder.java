// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.api;

import com.swirlds.config.api.converter.ConfigConverter;
import com.swirlds.config.api.intern.ConfigurationProvider;
import com.swirlds.config.api.source.ConfigSource;
import com.swirlds.config.api.validation.ConfigValidator;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * The {@link ConfigurationBuilder} is the main entry point to the config api since it is used to create a
 * {@link Configuration} instance. A new builder can be created by calling {@link #create()} and must be used to setup a
 * configuration by adding {@link ConfigSource}, {@link ConfigConverter}, {@link ConfigValidator} or config data type
 * (see {@link ConfigData}) instance. By calling {@link #build()} a new {@link Configuration} instance will be created
 * based on the defined setup.
 */
public interface ConfigurationBuilder {

    /**
     * The ordinal of properties specified via {@link #withValue(String, String)}.
     */
    int CUSTOM_PROPERTY_ORDINAL = 50;

    /**
     * Adds a config source (see {@link ConfigSource}). If this method is called after the config has been created (see
     * {@link #build()}) a {@link IllegalStateException} will be thrown.
     *
     * @param configSource the config source that should be used for the configuration
     * @return the builder instance (useful for fluent API)
     * @throws IllegalStateException if this method is called after the config has been created
     */
    @NonNull
    ConfigurationBuilder withSource(@NonNull ConfigSource configSource) throws IllegalStateException;

    /**
     * Adds config sources (see {@link ConfigSource}). If this method is called after the config has been created (see
     * {@link #build()}) a {@link IllegalStateException} will be thrown.
     *
     * @param configSources the config sources that should be used for the configuration
     * @return the builder instance (useful for fluent API)
     * @throws IllegalStateException if this method is called after the config has been created
     */
    @NonNull
    ConfigurationBuilder withSources(@NonNull ConfigSource... configSources) throws IllegalStateException;

    /**
     * Adds a converter (see {@link ConfigConverter}). If this method is called after the config has been created (see
     * {@link #build()}) a {@link IllegalStateException} will be thrown.
     *
     * @param converter the converter that should be used for the configuration
     * @return the builder instance (useful for fluent API)
     * @throws IllegalStateException if this method is called after the config has been created
     * @deprecated Use {@link ConfigurationBuilder#withConverter(Class, ConfigConverter)}
     */
    @NonNull
    @Deprecated(forRemoval = true)
    ConfigurationBuilder withConverter(@NonNull ConfigConverter<?> converter) throws IllegalStateException;

    /**
     * Adds a converter (see {@link ConfigConverter}). If this method is called after the config has been created (see
     * {@link #build()}) a {@link IllegalStateException} will be thrown.
     *
     * @param converterType the type to convert to
     * @param converter     the converter that should be used for the configuration
     * @return the builder instance (useful for fluent API)
     * @throws IllegalStateException if this method is called after the config has been created
     */
    @NonNull
    <T> ConfigurationBuilder withConverter(@NonNull Class<T> converterType, @NonNull ConfigConverter<T> converter)
            throws IllegalStateException;

    /**
     * Adds converters (see {@link ConfigConverter}). If this method is called after the config has been created (see
     * {@link #build()}) a {@link IllegalStateException} will be thrown.
     *
     * @param converters the converters that should be used for the configuration
     * @return the builder instance (useful for fluent API)
     * @throws IllegalStateException if this method is called after the config has been created
     * @deprecated Use {@link ConfigurationBuilder#withConverter(Class, ConfigConverter)}
     */
    @NonNull
    @Deprecated(forRemoval = true)
    ConfigurationBuilder withConverters(@NonNull ConfigConverter<?>... converters) throws IllegalStateException;

    /**
     * Adds a validator (see {@link ConfigValidator}). If this method is called after the config has been created (see
     * {@link #build()}) a {@link IllegalStateException} will be thrown.
     *
     * @param validator the validator that should be used for the configuration
     * @return the builder instance (useful for fluent API)
     * @throws IllegalStateException if this method is called after the config has been created
     */
    @NonNull
    ConfigurationBuilder withValidator(@NonNull ConfigValidator validator) throws IllegalStateException;

    /**
     * Adds validators (see {@link ConfigValidator}). If this method is called after the config has been created (see
     * {@link #build()}) a {@link IllegalStateException} will be thrown.
     *
     * @param validators the validators that should be used for the configuration
     * @return the builder instance (useful for fluent API)
     * @throws IllegalStateException if this method is called after the config has been created
     */
    @NonNull
    ConfigurationBuilder withValidators(@NonNull ConfigValidator... validators) throws IllegalStateException;

    /**
     * Adds a config data type (see {@link ConfigData}). If this method is called after the config has been created (see
     * {@link #build()}) a {@link IllegalStateException} will be thrown.
     *
     * @param type the config data type that should be used for the configuration
     * @return the builder instance (useful for fluent API)
     * @throws IllegalStateException if this method is called after the config has been created
     */
    @NonNull
    <T extends Record> ConfigurationBuilder withConfigDataType(@NonNull Class<T> type) throws IllegalStateException;

    /**
     * Adds config data types (see {@link ConfigData}). If this method is called after the config has been created (see
     * {@link #build()}) a {@link IllegalStateException} will be thrown.
     *
     * @param types the config data types that should be used for the configuration
     * @return the builder instance (useful for fluent API)
     * @throws IllegalStateException if this method is called after the config has been created
     */
    @NonNull
    ConfigurationBuilder withConfigDataTypes(@NonNull Class<? extends Record>... types) throws IllegalStateException;

    /**
     * Adds a property. In the created {@link Configuration} the property will be defined by an internal config source
     * that has the defined ordinal of {@link #CUSTOM_PROPERTY_ORDINAL}.
     *
     * @param propertyName name of the property
     * @param value        the value of the property
     * @return the {@link ConfigurationBuilder} instance (for fluent API)
     */
    @NonNull
    ConfigurationBuilder withValue(@NonNull String propertyName, @NonNull String value);

    /**
     * Creates a {@link Configuration} instance based on this builder.
     *
     * @return a new {@link Configuration} instance
     * @throws IllegalStateException If the method has already been called
     */
    @NonNull
    Configuration build();

    /**
     * This is the main entry point to us the config API. By calling this method a new {@link ConfigurationBuilder} is
     * created that can be used to create a {@link Configuration}.
     *
     * @return a new {@link ConfigurationBuilder} instance
     */
    @NonNull
    static ConfigurationBuilder create() {
        return ConfigurationProvider.getInstance().createBuilder();
    }

    /**
     * This method is used to automatically discover all extensions that are available in the classpath/modulepath. This
     * is done by using SPI (Service Provider Interface) and the {@link java.util.ServiceLoader} to find all
     * implementations of {@link ConfigurationExtension} and register all provided extensions.
     *
     * @return the {@link ConfigurationBuilder} instance (for fluent API)
     */
    @NonNull
    ConfigurationBuilder autoDiscoverExtensions();

    /**
     * This method loads a configuration extension.
     *
     * @param extension the extension to load
     * @return the {@link ConfigurationBuilder} instance (for fluent API)
     * @deprecated Avoid use of this method, this API will not be supported in the long term
     */
    @Deprecated
    @NonNull
    ConfigurationBuilder loadExtension(@NonNull final ConfigurationExtension extension);
}
