// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.converter;

import com.hedera.node.config.types.EntityScaleFactors;
import com.swirlds.config.api.converter.ConfigConverter;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Config api {@link ConfigConverter} implementation for the type {@link EntityScaleFactors}.
 */
public class EntityScaleFactorsConverter implements ConfigConverter<EntityScaleFactors> {

    @Override
    public EntityScaleFactors convert(@NonNull final String value)
            throws IllegalArgumentException, NullPointerException {
        if (value == null) {
            throw new NullPointerException("null can not be converted");
        }
        return EntityScaleFactors.from(value);
    }
}
