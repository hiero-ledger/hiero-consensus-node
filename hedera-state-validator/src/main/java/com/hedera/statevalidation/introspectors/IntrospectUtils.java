// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.introspectors;

import com.hedera.pbj.runtime.JsonCodec;
import java.lang.reflect.Field;

public final class IntrospectUtils {

    public static JsonCodec getCodecFor(final Object pbjObject) {
        try {
            Field jsonCodecField = pbjObject.getClass().getDeclaredField("JSON");
            return (JsonCodec) jsonCodecField.get(null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private IntrospectUtils() {}
}
