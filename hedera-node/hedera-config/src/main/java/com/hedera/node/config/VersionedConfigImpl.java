// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config;

import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Collection;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Implementation of {@link VersionedConfiguration} that wraps a {@link Configuration} and a version.
 */
public class VersionedConfigImpl implements VersionedConfiguration {

    private final Configuration wrappedConfig;

    private final long version;

    public VersionedConfigImpl(@NonNull final Configuration wrappedConfig, final long version) {
        this.wrappedConfig = Objects.requireNonNull(wrappedConfig, "wrappedConfig must not be null");
        this.version = version;
    }

    @Override
    public long getVersion() {
        return version;
    }

    @Override
    @NonNull
    public Stream<String> getPropertyNames() {
        return wrappedConfig.getPropertyNames();
    }

    @Override
    public boolean exists(@NonNull final String s) {
        return wrappedConfig.exists(s);
    }

    @Override
    public boolean isListValue(@NonNull final String propertyName) {
        return wrappedConfig.isListValue(propertyName);
    }

    @Override
    public String getValue(@NonNull final String s) throws NoSuchElementException {
        return wrappedConfig.getValue(s);
    }

    @Override
    public String getValue(@NonNull final String s, final String s1) {
        return wrappedConfig.getValue(s, s1);
    }

    @Override
    public <T> T getValue(@NonNull final String s, @NonNull final Class<T> aClass)
            throws NoSuchElementException, IllegalArgumentException {
        return wrappedConfig.getValue(s, aClass);
    }

    @Override
    public <T> T getValue(@NonNull final String s, @NonNull final Class<T> aClass, final T t)
            throws IllegalArgumentException {
        return wrappedConfig.getValue(s, aClass, t);
    }

    @Override
    public List<String> getValues(@NonNull final String s) {
        return wrappedConfig.getValues(s);
    }

    @Override
    public List<String> getValues(@NonNull final String s, final List<String> list) {
        return wrappedConfig.getValues(s, list);
    }

    @Override
    public <T> List<T> getValues(@NonNull final String s, @NonNull final Class<T> aClass)
            throws NoSuchElementException, IllegalArgumentException {
        return wrappedConfig.getValues(s, aClass);
    }

    @Override
    public <T> List<T> getValues(@NonNull final String s, @NonNull final Class<T> aClass, final List<T> list)
            throws IllegalArgumentException {
        return wrappedConfig.getValues(s, aClass, list);
    }

    @Override
    @NonNull
    public <T extends Record> T getConfigData(@NonNull final Class<T> aClass) {
        return wrappedConfig.getConfigData(aClass);
    }

    @Override
    @NonNull
    public Collection<Class<? extends Record>> getConfigDataTypes() {
        return wrappedConfig.getConfigDataTypes();
    }

    @Override
    public Set<String> getValueSet(@NonNull String propertyName) {
        return wrappedConfig.getValueSet(propertyName);
    }

    @Override
    public Set<String> getValueSet(@NonNull String propertyName, @Nullable Set<String> defaultValue) {
        return wrappedConfig.getValueSet(propertyName, defaultValue);
    }

    @Override
    public <T> Set<T> getValueSet(@NonNull String propertyName, @NonNull Class<T> propertyType)
            throws NoSuchElementException, IllegalArgumentException {
        return wrappedConfig.getValueSet(propertyName, propertyType);
    }

    @Override
    public <T> Set<T> getValueSet(
            @NonNull String propertyName, @NonNull Class<T> propertyType, @Nullable Set<T> defaultValue)
            throws IllegalArgumentException {
        return wrappedConfig.getValueSet(propertyName, propertyType, defaultValue);
    }
}
