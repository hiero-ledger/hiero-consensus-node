// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.validators;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ADMIN_KEY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.KEY_REQUIRED;
import static com.hedera.node.app.hapi.utils.keys.KeyUtils.IMMUTABILITY_SENTINEL_KEY;
import static com.hedera.node.app.hapi.utils.keys.KeyUtils.isValid;
import static com.hedera.node.app.service.token.impl.handlers.BaseTokenHandler.UNLIMITED_AUTOMATIC_ASSOCIATIONS;

import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.node.app.hapi.utils.keys.KeyUtils;
import com.hedera.node.app.spi.validation.AttributeValidator;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.config.data.EntitiesConfig;
import com.hedera.node.config.data.LedgerConfig;
import com.hedera.node.config.data.TokensConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Provides validation for token fields like token type,  token supply type, token symbol etc.,
 * It is used in pureChecks for token creation.
 */
@Singleton
public class CryptoCreateValidator {
    /**
     * Default constructor for injection.
     */
    @Inject
    public CryptoCreateValidator() {
        // Exists for injection
    }

    /**
     * Validates the key.
     *
     * <p>If the key is dispatched internally, then it is allowed to use {@link KeyUtils#IMMUTABILITY_SENTINEL_KEY} as
     * its key. Otherwise, this key is disallowed. Otherwise, we throw {@link HandleException} with
     * {@link ResponseCodeEnum#BAD_ENCODING} if the key is empty or exceeds the maximum key depth. All other invalid
     * scenarios throw {@link HandleException} with {@link ResponseCodeEnum#INVALID_ADMIN_KEY}.
     *
     * @param key                The key to validate
     * @param attributeValidator AttributeValidator
     * @param isInternalDispatch Whether this is a hollow account creation (permits empty key list)
     * @throws HandleException If the inputs are not invalid
     */
    public void validateKey(
            @NonNull final Key key,
            @NonNull final AttributeValidator attributeValidator,
            final boolean isInternalDispatch) {

        final var isSentinel = IMMUTABILITY_SENTINEL_KEY.equals(key);
        if (isSentinel && !isInternalDispatch) {
            // IMMUTABILITY_SENTINEL_KEY is only allowed for internal dispatches.
            throw new HandleException(KEY_REQUIRED);
        } else if (!isSentinel) {
            // If it is not the sentinel key, we need to validate the key, no matter whether internal or HAPI.
            //
            // This solution is not nice, but for now, it is the best we can do and maintain compatibility and some
            // semblance of maintainability. There is a lot of duplicated work, because `isEmpty` is called by
            // `isValid(Key)`, and `isValid(Key)` is called by `validateKey(Key)`! So `isEmpty` gets called at least
            // three times (once in pureChecks), and `isValid` at least twice. But this is the only way to make sure the
            // right exceptions are thrown, without breaking key validation steps down in a granular way which would be
            // hard to maintain.
            if (!isValid(key)) {
                throw new HandleException(INVALID_ADMIN_KEY);
            } else {
                attributeValidator.validateKey(key);
            }
        }
    }

    /** Check if the number of auto associations is too many
     * or in the case of unlimited auto associations, check if the number is less than -1 or 0 if disabled.
     * @param numAssociations number to check
     * @param ledgerConfig LedgerConfig
     * @param entitiesConfig EntitiesConfig
     * @param tokensConfig TokensConfig
     * @return true the given number is greater than the max number of auto associations
     * or negative and unlimited auto associations are disabled
     * or less than -1 if unlimited auto associations are enabled
     */
    public boolean tooManyAutoAssociations(
            final int numAssociations,
            @NonNull final LedgerConfig ledgerConfig,
            @NonNull final EntitiesConfig entitiesConfig,
            @NonNull final TokensConfig tokensConfig) {
        return (entitiesConfig.limitTokenAssociations() && numAssociations > tokensConfig.maxPerAccount())
                || numAssociations > ledgerConfig.maxAutoAssociations()
                || (numAssociations < UNLIMITED_AUTOMATIC_ASSOCIATIONS
                        && entitiesConfig.unlimitedAutoAssociationsEnabled())
                || (numAssociations < 0 && !entitiesConfig.unlimitedAutoAssociationsEnabled());
    }
}
