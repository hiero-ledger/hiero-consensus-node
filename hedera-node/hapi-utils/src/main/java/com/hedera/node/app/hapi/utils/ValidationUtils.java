// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils;

import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import java.util.function.Supplier;

/**
 * A minimalist collection of helpers to improve readability of code that throws a
 * {@link InvalidTransactionException} when a validation check fails.
 */
public final class ValidationUtils {
    private ValidationUtils() {
        throw new UnsupportedOperationException("Utility Class");
    }

    public static void validateTrue(final boolean flag, final ResponseCodeEnum code) {
        if (!flag) {
            throw new InvalidTransactionException(code);
        }
    }

    public static void validateTrueOrRevert(final boolean flag, final ResponseCodeEnum code) {
        if (!flag) {
            throw new InvalidTransactionException(code, true);
        }
    }

    public static void validateTrue(final boolean flag, final ResponseCodeEnum code, final String failureMsg) {
        if (!flag) {
            throw new InvalidTransactionException(failureMsg, code);
        }
    }

    public static void validateTrue(
            final boolean flag, final ResponseCodeEnum code, final Supplier<String> failureMsg) {
        if (!flag) {
            throw new InvalidTransactionException(failureMsg.get(), code);
        }
    }

    public static void validateFalse(final boolean flag, final ResponseCodeEnum code) {
        if (flag) {
            throw new InvalidTransactionException(code);
        }
    }

    public static void validateFalse(final boolean flag, final ResponseCodeEnum code, final String failureMsg) {
        if (flag) {
            throw new InvalidTransactionException(failureMsg, code);
        }
    }

    public static void validateFalse(
            final boolean flag, final ResponseCodeEnum code, final Supplier<String> failureMsg) {
        if (flag) {
            throw new InvalidTransactionException(failureMsg.get(), code);
        }
    }

    public static void validateFalseOrRevert(final boolean flag, final ResponseCodeEnum code) {
        if (flag) {
            throw new InvalidTransactionException(code, true);
        }
    }
}
