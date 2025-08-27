// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.validation;

import static com.hedera.node.app.hapi.utils.keys.KeyUtils.IMMUTABILITY_SENTINEL_KEY;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.node.app.hapi.utils.keys.KeyUtils;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.TransactionHandler;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * A type that any {@link TransactionHandler} can use to validate entity
 * attributes like memos or keys.
 */
public interface AttributeValidator {
    int MAX_NESTED_KEY_LEVELS = 15;

    /**
     * Validates the given key. If the key is more than allowed depth, throws {@code ResponseCodeEnum.BAD_ENCODING}
     * Then validates each key in the given structure. If the key is not valid throws {@code ResponseCodeEnum.BAD_ENCODING}
     *
     * @param key the key to validate
     * @throws HandleException if the key is invalid or more than {@value MAX_NESTED_KEY_LEVELS}
     */
    void validateKey(@NonNull Key key);

    /**
     * Validates the given key. If the key is more than allowed depth, throws responseCodeEnum
     *
     * @param key the key to validate
     * @param responseCodeEnum the response code to throw if the key is invalid
     */
    void validateKey(@NonNull Key key, @NonNull ResponseCodeEnum responseCodeEnum);

    /**
     * Validates the given memo.
     *
     * @param memo the memo to validate
     * @throws HandleException if the key is invalid
     */
    void validateMemo(@Nullable String memo);

    /**
     * Validates the given expiry.
     *
     * @param expiry the expiry to validate
     * @throws HandleException if the expiry is invalid
     */
    void validateExpiry(long expiry);

    /**
     * Validates the given auto-renew period.
     *
     * @param autoRenewPeriod the auto-renew period to validate
     * @throws HandleException if the auto-renew period is invalid
     */
    void validateAutoRenewPeriod(long autoRenewPeriod);

    /**
     * Validates if immutable entity with the key
     *
     * @param key the key to validate
     * @return true if immutable entity with the key
     */
    static boolean isImmutableKey(@NonNull Key key) {
        requireNonNull(key);
        return key.hasKeyList() && key.equals(IMMUTABILITY_SENTINEL_KEY);
    }

    /**
     * Checks if the given key is a key removal, if it is set as {@link KeyUtils#IMMUTABILITY_SENTINEL_KEY}.
     * @param source the key to check
     * @return true if the key is a key removal, false otherwise
     */
    static boolean isKeyRemoval(@NonNull final Key source) {
        requireNonNull(source);
        return isImmutableKey(source);
    }
}
