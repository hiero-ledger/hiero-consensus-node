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

    @NonNull
    @Override
    public SemanticVersion convert(@NonNull String value) throws IllegalArgumentException, NullPointerException {
        return HapiUtils.fromString(value);
    }
}
