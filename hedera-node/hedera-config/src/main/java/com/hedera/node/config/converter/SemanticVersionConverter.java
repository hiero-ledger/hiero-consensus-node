// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.converter;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.util.HapiUtils;
import com.swirlds.config.api.converter.ConfigConverter;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A {@link ConfigConverter} that converts a {@link String} to a {@link SemanticVersion}. The {@link String} must be
 * formatted according to the <a href="https://semver.org/">Semantic Versioning 2.0.0</a> specification.
 */
public final class SemanticVersionConverter implements ConfigConverter<SemanticVersion> {
    /** Arbitrary limit to prevent stack overflow when parsing unrealistically long versions. */
    private static final int MAX_VERSION_LENGTH = 100;

    @NonNull
    @Override
    public SemanticVersion convert(@NonNull String value) throws IllegalArgumentException, NullPointerException {
        if (value.length() > MAX_VERSION_LENGTH) {
            throw new IllegalArgumentException("Semantic version '" + value + "' is too long");
        }

        return HapiUtils.fromString(value);
    }
}
