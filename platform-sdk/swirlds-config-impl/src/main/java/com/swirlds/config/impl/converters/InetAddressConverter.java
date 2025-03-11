// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.impl.converters;

import com.swirlds.config.api.converter.ConfigConverter;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Objects;

/**
 * A {@link ConfigConverter} that converts a string to an {@link InetAddress}.
 */
public class InetAddressConverter implements ConfigConverter<InetAddress> {

    /**
     * {{@inheritDoc}}
     */
    @Nullable
    @Override
    public InetAddress convert(@NonNull final String value) throws IllegalArgumentException, NullPointerException {
        Objects.requireNonNull(value, "value must not be null");
        try {
            return InetAddress.getByName(value);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
