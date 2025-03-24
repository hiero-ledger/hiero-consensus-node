// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.converter;

import static org.hiero.consensus.model.utility.CommonUtils.unhex;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.converter.ConfigConverter;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Converts a string of hex digits into a {@link Bytes} object.
 */
public class BytesConverter implements ConfigConverter<Bytes> {
    @Nullable
    @Override
    public Bytes convert(@NonNull String value) throws IllegalArgumentException, NullPointerException {
        if (value == null) {
            throw new NullPointerException("BytesConverter cannot convert null value");
        } else {
            try {
                final var hex = value.startsWith("0x") ? value.substring(2) : value;
                final var bytes = unhex(hex);
                return Bytes.wrap(bytes);
            } catch (final Exception e) {
                throw new IllegalArgumentException("Invalid format: " + value, e);
            }
        }
    }
}
