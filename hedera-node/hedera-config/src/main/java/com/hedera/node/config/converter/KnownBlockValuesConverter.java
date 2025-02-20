// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.converter;

import com.hedera.node.app.hapi.utils.sysfiles.domain.KnownBlockValues;
import com.swirlds.config.api.converter.ConfigConverter;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Config api {@link ConfigConverter} implementation for the type {@link KnownBlockValues}.
 */
public class KnownBlockValuesConverter implements ConfigConverter<KnownBlockValues> {

    @Override
    public KnownBlockValues convert(@NonNull final String value) throws IllegalArgumentException, NullPointerException {
        if (value == null) {
            throw new NullPointerException("null can not be converted");
        }
        return KnownBlockValues.from(value);
    }
}
