// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.converter;

import com.hedera.node.app.hapi.utils.sysfiles.domain.throttling.ScaleFactor;
import com.swirlds.config.api.converter.ConfigConverter;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Config api {@link ConfigConverter} implementation for the type {@link ScaleFactor}.
 */
public class ScaleFactorConverter implements ConfigConverter<ScaleFactor> {

    @Override
    public ScaleFactor convert(@NonNull final String value) throws IllegalArgumentException, NullPointerException {
        if (value == null) {
            throw new NullPointerException("null can not be converted");
        }
        return ScaleFactor.from(value);
    }
}
