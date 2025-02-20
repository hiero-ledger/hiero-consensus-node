// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.converter;

import static java.util.Objects.requireNonNull;

import com.hedera.node.config.types.PermissionedAccountsRange;
import com.swirlds.config.api.converter.ConfigConverter;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Implementation of the {@link ConfigConverter} api that adds support for the {@link PermissionedAccountsRange} type to
 * the config api. The method {@link PermissionedAccountsRange#from(String)} is used to parse the string value. Instead
 * of returning {@code null} when {@link PermissionedAccountsRange#from(String)} returns {@code null}, this converter
 * throws an {@code IllegalArgumentException} in that case.
 */
public final class PermissionedAccountsRangeConverter implements ConfigConverter<PermissionedAccountsRange> {

    /**
     * {@inheritDoc}
     */
    @Nullable
    @Override
    public PermissionedAccountsRange convert(@NonNull final String value)
            throws IllegalArgumentException, NullPointerException {
        requireNonNull(value, "'null' can not be converted");
        final var converted = PermissionedAccountsRange.from(value);
        if (converted == null) {
            throw new IllegalArgumentException("Invalid PermissionedAccountsRange value: " + value);
        }
        return converted;
    }
}
