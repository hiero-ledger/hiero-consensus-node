// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.validation;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.node.app.spi.workflows.PreCheckException;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * A utility class, similar in concept {@link java.util.Objects#requireNonNull(Object)}, to validate or throw
 * exceptions.
 */
public final class Validations {

    /** No instantiation permitted */
    private Validations() {}

    /**
     * Checks that the given subject is not null. If it is, then a {@link PreCheckException} is thrown with the
     * given {@link ResponseCodeEnum}.
     *
     * @param subject The object to check.
     * @param code The {@link ResponseCodeEnum} to use if the subject is null.
     * @return The subject if it is not null.
     * @param <T> The type of the subject.
     * @throws PreCheckException If the subject is null, a {@link PreCheckException} is thrown with the given
     * {@link ResponseCodeEnum}.
     */
    public static <T> T mustExist(@Nullable final T subject, @NonNull final ResponseCodeEnum code)
            throws PreCheckException {
        if (subject == null) {
            throw new PreCheckException(code);
        }

        return subject;
    }

    /**
     * Common validation of an {@link AccountID} that it is internally consistent. A valid ID must not be null,
     * must have either an alias or an account number, and if it has an account number, it must be positive. And
     * if there is an alias, it must have at least one byte.
     *
     * @param subject The {@link AccountID} to validate.
     * @return The {@link AccountID} if valid.
     * @throws PreCheckException If the account ID is not valid, {@link ResponseCodeEnum#INVALID_ACCOUNT_ID} will
     * be thrown.
     */
    @NonNull
    public static AccountID validateAccountID(
            @Nullable final AccountID subject, @Nullable ResponseCodeEnum responseCodeEnum) throws PreCheckException {
        AccountID result = null;
        try {
            result = validateNullableAccountID(subject);
            // Cannot be null
            if (result == null) {
                throw new PreCheckException(
                        responseCodeEnum == null ? ResponseCodeEnum.INVALID_ACCOUNT_ID : responseCodeEnum);
            }
        } catch (PreCheckException e) {
            if (responseCodeEnum != null) {
                throw new PreCheckException(responseCodeEnum);
            } else throw e;
        }
        return result;
    }

    /**
     * Common validation of an {@link AccountID} that it is internally consistent, but which permits an account ID to
     * be null. A valid ID must be null, or have either an alias or an account number, and if it has an account number,
     * it must be positive. And if there is an alias instead, it must have at least one byte.
     *
     * @param subject The {@link AccountID} to validate.
     * @return The {@link AccountID} if valid.
     * @throws PreCheckException If the account ID is not valid, {@link ResponseCodeEnum#INVALID_ACCOUNT_ID} will
     * be thrown.
     */
    @Nullable
    public static AccountID validateNullableAccountID(@Nullable final AccountID subject) throws PreCheckException {
        // We'll permit it to be null.
        if (subject == null) {
            return null;
        }

        // The account ID must have the account type (number or alias) set. It cannot be UNSET.
        if (subject.account().kind() == AccountID.AccountOneOfType.UNSET) {
            throw new PreCheckException(ResponseCodeEnum.INVALID_ACCOUNT_ID);
        }

        // You cannot have negative or zero account numbers. Those just aren't allowed!
        if (subject.hasAccountNum() && subject.accountNumOrThrow() <= 0) {
            throw new PreCheckException(ResponseCodeEnum.INVALID_ACCOUNT_ID);
        }

        // And if you have an alias, it has to have at least a byte.
        if (subject.hasAlias()) {
            final var alias = subject.aliasOrThrow();
            if (alias.length() < 1) {
                throw new PreCheckException(ResponseCodeEnum.INVALID_ACCOUNT_ID);
            }
        }

        return subject;
    }
}
