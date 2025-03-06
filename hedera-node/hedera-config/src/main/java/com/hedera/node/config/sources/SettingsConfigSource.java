// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.sources;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.Setting;
import com.swirlds.config.extensions.sources.AbstractConfigSource;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SettingsConfigSource extends AbstractConfigSource {

    private final Map<String, String> properties;
    private final int ordinal;

    public SettingsConfigSource(@NonNull final List<Setting> settings, int ordinal) {
        requireNonNull(settings, "settings must not be null");
        this.ordinal = ordinal;

        final var mutableMap = new HashMap<String, String>();
        for (final var setting : settings) {
            mutableMap.put(setting.name(), setting.value());
        }

        properties = Collections.unmodifiableMap(mutableMap);
    }

    @Override
    protected Map<String, String> getInternalProperties() {
        return properties;
    }

    @Override
    public int getOrdinal() {
        return ordinal;
    }
}
