// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.jackson;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;
import java.time.Instant;

/**
 * Deserializer for optionally present {@link Instant} values.
 */
public class InstantDeserializer extends StdDeserializer<Instant> {
    public InstantDeserializer() {
        this(null);
    }

    public InstantDeserializer(final Class<?> vc) {
        super(vc);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Returns {@code null} if a value is not present.
     */
    @Override
    public Instant deserialize(final JsonParser jp, final DeserializationContext ctxt) throws IOException {
        final String value = jp.getValueAsString();
        if (value == null || value.isBlank()) {
            return null;
        }
        return Instant.parse(value);
    }
}
