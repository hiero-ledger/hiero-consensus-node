// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.sources;

import com.swirlds.config.api.source.ConfigSource;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

public class DynamicConfigSource implements ConfigSource {

    private final Properties properties;

    public DynamicConfigSource() {
        this.properties = new Properties();
    }

    public void setProperty(@NonNull final String name, @Nullable final String value) {
        Objects.requireNonNull(name, "name cannot be null");
        properties.setProperty(name, value);
    }

    public void removeProperty(@NonNull final String name) {
        Objects.requireNonNull(name, "name cannot be null");
        properties.remove(name);
    }

    @NonNull
    @Override
    public Set<String> getPropertyNames() {
        return properties.stringPropertyNames();
    }

    @Nullable
    @Override
    public String getValue(@NonNull final String s) throws NoSuchElementException {
        return properties.getProperty(s);
    }

    @Override
    public boolean isListProperty(@NonNull final String propertyName) throws NoSuchElementException {
        return false;
    }

    @NonNull
    @Override
    public List<String> getListValue(@NonNull final String propertyName) throws NoSuchElementException {
        return List.of();
    }

    @Override
    public int getOrdinal() {
        return 500;
    }
}
