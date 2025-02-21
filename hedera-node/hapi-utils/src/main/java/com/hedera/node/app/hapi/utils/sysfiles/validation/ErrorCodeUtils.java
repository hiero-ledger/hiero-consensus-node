// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.sysfiles.validation;

import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import java.util.Optional;

public final class ErrorCodeUtils {
    private static final String EXC_MSG_TPL = "%s :: %s";

    private ErrorCodeUtils() {
        throw new UnsupportedOperationException("Utility Class");
    }

    public static String exceptionMsgFor(final ResponseCodeEnum error, final String details) {
        return String.format(EXC_MSG_TPL, error, details);
    }

    public static Optional<ResponseCodeEnum> errorFrom(final String exceptionMsg) {
        final var parts = exceptionMsg.split(" :: ");
        if (parts.length != 2) {
            return Optional.empty();
        }
        return Optional.of(ResponseCodeEnum.valueOf(parts[0]));
    }
}
