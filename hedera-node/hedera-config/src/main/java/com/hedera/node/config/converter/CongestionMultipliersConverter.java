// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.converter;

import com.hedera.node.config.types.CongestionMultipliers;
import com.swirlds.config.api.converter.ConfigConverter;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Config api {@link ConfigConverter} implementation for the type {@link CongestionMultipliers}.
 */
public class CongestionMultipliersConverter implements ConfigConverter<CongestionMultipliers> {

    @Override
    public CongestionMultipliers convert(@NonNull final String value)
            throws IllegalArgumentException, NullPointerException {
        if (value == null) {
            throw new NullPointerException("null can not be converted");
        }
        return CongestionMultipliers.from(value);
    }
}
