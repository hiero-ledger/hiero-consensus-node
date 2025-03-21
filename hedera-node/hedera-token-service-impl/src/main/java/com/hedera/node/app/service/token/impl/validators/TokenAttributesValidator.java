// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.validators;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ADMIN_KEY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_CUSTOM_FEE_SCHEDULE_KEY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_FREEZE_KEY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_KYC_KEY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_METADATA_KEY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_PAUSE_KEY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_SUPPLY_KEY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_WIPE_KEY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ZERO_BYTE_IN_STRING;
import static com.hedera.hapi.node.base.ResponseCodeEnum.METADATA_TOO_LONG;
import static com.hedera.hapi.node.base.ResponseCodeEnum.MISSING_TOKEN_NAME;
import static com.hedera.hapi.node.base.ResponseCodeEnum.MISSING_TOKEN_SYMBOL;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_NAME_TOO_LONG;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_SYMBOL_TOO_LONG;
import static com.hedera.node.app.hapi.utils.keys.KeyUtils.isValid;
import static com.hedera.node.app.spi.validation.AttributeValidator.isKeyRemoval;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;

import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.KeyList;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.node.config.data.TokensConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.nio.charset.StandardCharsets;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Provides validation for token fields like token type,  token supply type, token symbol etc.,.
 * It is used in pureChecks for token creation.
 */
@Singleton
public class TokenAttributesValidator {
    /**
     * Default constructor for injection.
     */
    @Inject
    public TokenAttributesValidator() {
        // Dagger
    }

    /**
     * Validates the token symbol, if it exists and is not empty or not too long.
     * @param symbol the token symbol to validate
     * @param tokensConfig the tokens configuration
     */
    public void validateTokenSymbol(@Nullable final String symbol, @NonNull final TokensConfig tokensConfig) {
        tokenStringCheck(symbol, tokensConfig.maxSymbolUtf8Bytes(), MISSING_TOKEN_SYMBOL, TOKEN_SYMBOL_TOO_LONG);
    }

    /**
     * Validates the token name, if it is exists and is not empty or not too long.
     * @param name the token name to validate
     * @param tokensConfig the tokens configuration
     */
    public void validateTokenName(@Nullable final String name, @NonNull final TokensConfig tokensConfig) {
        tokenStringCheck(name, tokensConfig.maxTokenNameUtf8Bytes(), MISSING_TOKEN_NAME, TOKEN_NAME_TOO_LONG);
    }

    /**
     * Validates the token metadata, if it exists and is not too long.
     * @param metadata the token metadata to validate
     * @param tokensConfig the tokens configuration
     */
    public void validateTokenMetadata(@NonNull final Bytes metadata, @NonNull final TokensConfig tokensConfig) {
        if (metadata.length() > 0) {
            validateTrue(metadata.length() <= tokensConfig.tokensMaxMetadataBytes(), METADATA_TOO_LONG);
        }
    }

    /**
     * Given a token name or token symbol, validates that it is not null, not empty, not too long, and does not contain
     * a zero byte.
     * @param s the token name or symbol to validate
     * @param maxLen the maximum number of UTF-8 bytes allowed in the token name or symbol
     * @param onMissing the response code to use if the token name or symbol is null or empty
     * @param onTooLong the response code to use if the token name or symbol is too long
     */
    private void tokenStringCheck(
            @Nullable final String s,
            final int maxLen,
            @NonNull final ResponseCodeEnum onMissing,
            @NonNull final ResponseCodeEnum onTooLong) {
        validateTrue(s != null, onMissing);
        final int numUtf8Bytes = s.getBytes(StandardCharsets.UTF_8).length;
        validateTrue(numUtf8Bytes != 0, onMissing);
        validateTrue(numUtf8Bytes <= maxLen, onTooLong);
        validateTrue(!s.contains("\u0000"), INVALID_ZERO_BYTE_IN_STRING);
    }

    /**
     * Validates the token keys, if it is exists and is not empty or not too long.
     * For token admin key, allows empty {@link KeyList} to be set. It is used for removing keys.
     * This method is both used in TokenCreate and TokenUpdate.
     *
     * @param hasAdminKey whether the token has an admin key
     * @param adminKey the token admin key to validate
     * @param hasKycKey whether the token has a KYC key
     * @param kycKey the token KYC key to validate
     * @param hasWipeKey whether the token has a wipe key
     * @param wipeKey the token wipe key to validate
     * @param hasSupplyKey whether the token has a supply key
     * @param supplyKey the token supply key to validate
     * @param hasFreezeKey whether the token has a freeze key
     * @param freezeKey the token freeze key to validate
     * @param hasFeeScheduleKey whether the token has a fee schedule key
     * @param feeScheduleKey the token fee schedule key to validate
     * @param hasPauseKey whether the token has a pause key
     * @param pauseKey the token pause key to validate
     * @param hasMetadataKey whether the token has a metadata key
     * @param metadataKey the token metadata key to validate
     */
    public void validateTokenKeys(
            final boolean hasAdminKey,
            @Nullable final Key adminKey,
            final boolean hasKycKey,
            @Nullable final Key kycKey,
            final boolean hasWipeKey,
            @Nullable final Key wipeKey,
            final boolean hasSupplyKey,
            @Nullable final Key supplyKey,
            final boolean hasFreezeKey,
            @Nullable final Key freezeKey,
            final boolean hasFeeScheduleKey,
            @Nullable final Key feeScheduleKey,
            final boolean hasPauseKey,
            @Nullable final Key pauseKey,
            final boolean hasMetadataKey,
            @Nullable final Key metadataKey) {
        if (hasAdminKey && !isKeyRemoval(adminKey)) {
            validateTrue(isValid(adminKey), INVALID_ADMIN_KEY);
        }
        if (hasKycKey && !isKeyRemoval(kycKey)) {
            validateTrue(isValid(kycKey), INVALID_KYC_KEY);
        }
        if (hasWipeKey && !isKeyRemoval(wipeKey)) {
            validateTrue(isValid(wipeKey), INVALID_WIPE_KEY);
        }
        if (hasSupplyKey && !isKeyRemoval(supplyKey)) {
            validateTrue(isValid(supplyKey), INVALID_SUPPLY_KEY);
        }
        if (hasFreezeKey && !isKeyRemoval(freezeKey)) {
            validateTrue(isValid(freezeKey), INVALID_FREEZE_KEY);
        }
        if (hasFeeScheduleKey && !isKeyRemoval(feeScheduleKey)) {
            validateTrue(isValid(feeScheduleKey), INVALID_CUSTOM_FEE_SCHEDULE_KEY);
        }
        if (hasPauseKey && !isKeyRemoval(pauseKey)) {
            validateTrue(isValid(pauseKey), INVALID_PAUSE_KEY);
        }
        if (hasMetadataKey && !isKeyRemoval(metadataKey)) {
            validateTrue(isValid(metadataKey), INVALID_METADATA_KEY);
        }
    }
}
