// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.util;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ADMIN_KEY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_CUSTOM_FEE_SCHEDULE_KEY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_FREEZE_KEY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_KYC_KEY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_METADATA_KEY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_PAUSE_KEY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_SUPPLY_KEY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_WIPE_KEY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_HAS_NO_ADMIN_KEY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_HAS_NO_FEE_SCHEDULE_KEY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_HAS_NO_FREEZE_KEY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_HAS_NO_KYC_KEY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_HAS_NO_METADATA_KEY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_HAS_NO_PAUSE_KEY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_HAS_NO_SUPPLY_KEY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_HAS_NO_WIPE_KEY;
import static com.hedera.node.app.spi.validation.AttributeValidator.isKeyRemoval;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;

import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.token.TokenUpdateTransactionBody;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * All the keys that can be updated on a Token. This provides a way to update token keys.
 */
public enum TokenKey {
    /**
     * The admin key.
     */
    ADMIN_KEY {
        @Override
        public boolean isPresentInUpdate(TokenUpdateTransactionBody update) {
            return update.hasAdminKey();
        }

        @Override
        public boolean isPresentInitially(Token originalToken) {
            return originalToken.hasAdminKey();
        }

        @Override
        public void setOn(Token.Builder builder, TokenUpdateTransactionBody update) {
            builder.adminKey(getNewKeyValue(update.adminKey()));
        }

        @Override
        public Key getFromUpdate(TokenUpdateTransactionBody update) {
            return update.adminKey();
        }

        @Override
        public Key getFromToken(Token originalToken) {
            return originalToken.adminKey();
        }

        @Override
        public ResponseCodeEnum tokenHasNoKeyStatus() {
            return TOKEN_HAS_NO_ADMIN_KEY;
        }

        @Override
        public ResponseCodeEnum invalidKeyStatus() {
            return INVALID_ADMIN_KEY;
        }
    },
    /**
     * The fee schedule key.
     */
    FEE_SCHEDULE_KEY {
        @Override
        public boolean isPresentInUpdate(TokenUpdateTransactionBody update) {
            return update.hasFeeScheduleKey();
        }

        @Override
        public boolean isPresentInitially(Token originalToken) {
            return originalToken.hasFeeScheduleKey();
        }

        @Override
        public void setOn(final Token.Builder builder, TokenUpdateTransactionBody update) {
            builder.feeScheduleKey(getNewKeyValue(update.feeScheduleKey()));
        }

        @Override
        public Key getFromUpdate(TokenUpdateTransactionBody update) {
            return update.feeScheduleKey();
        }

        @Override
        public Key getFromToken(Token originalToken) {
            return originalToken.feeScheduleKey();
        }

        @Override
        public ResponseCodeEnum tokenHasNoKeyStatus() {
            return TOKEN_HAS_NO_FEE_SCHEDULE_KEY;
        }

        @Override
        public ResponseCodeEnum invalidKeyStatus() {
            return INVALID_CUSTOM_FEE_SCHEDULE_KEY;
        }
    },
    /**
     * The supply key.
     */
    SUPPLY_KEY {
        @Override
        public boolean isPresentInUpdate(TokenUpdateTransactionBody update) {
            return update.hasSupplyKey();
        }

        @Override
        public boolean isPresentInitially(Token originalToken) {
            return originalToken.hasSupplyKey();
        }

        @Override
        public void setOn(final Token.Builder builder, TokenUpdateTransactionBody update) {
            builder.supplyKey(getNewKeyValue(update.supplyKey()));
        }

        @Override
        public Key getFromUpdate(TokenUpdateTransactionBody update) {
            return update.supplyKey();
        }

        @Override
        public Key getFromToken(Token originalToken) {
            return originalToken.supplyKey();
        }

        @Override
        public ResponseCodeEnum tokenHasNoKeyStatus() {
            return TOKEN_HAS_NO_SUPPLY_KEY;
        }

        @Override
        public ResponseCodeEnum invalidKeyStatus() {
            return INVALID_SUPPLY_KEY;
        }
    },
    /**
     * The wipe key.
     */
    WIPE_KEY {
        @Override
        public boolean isPresentInUpdate(TokenUpdateTransactionBody update) {
            return update.hasWipeKey();
        }

        @Override
        public boolean isPresentInitially(Token originalToken) {
            return originalToken.hasWipeKey();
        }

        @Override
        public void setOn(final Token.Builder builder, TokenUpdateTransactionBody update) {
            builder.wipeKey(getNewKeyValue(update.wipeKey()));
        }

        @Override
        public Key getFromUpdate(TokenUpdateTransactionBody update) {
            return update.wipeKey();
        }

        @Override
        public Key getFromToken(Token originalToken) {
            return originalToken.wipeKey();
        }

        @Override
        public ResponseCodeEnum tokenHasNoKeyStatus() {
            return TOKEN_HAS_NO_WIPE_KEY;
        }

        @Override
        public ResponseCodeEnum invalidKeyStatus() {
            return INVALID_WIPE_KEY;
        }
    },
    /**
     * The pause key.
     */
    PAUSE_KEY {
        @Override
        public boolean isPresentInUpdate(TokenUpdateTransactionBody update) {
            return update.hasPauseKey();
        }

        @Override
        public boolean isPresentInitially(Token originalToken) {
            return originalToken.hasPauseKey();
        }

        @Override
        public void setOn(final Token.Builder builder, TokenUpdateTransactionBody update) {
            builder.pauseKey(getNewKeyValue(update.pauseKey()));
        }

        @Override
        public Key getFromUpdate(TokenUpdateTransactionBody update) {
            return update.pauseKey();
        }

        @Override
        public Key getFromToken(Token originalToken) {
            return originalToken.pauseKey();
        }

        @Override
        public ResponseCodeEnum tokenHasNoKeyStatus() {
            return TOKEN_HAS_NO_PAUSE_KEY;
        }

        @Override
        public ResponseCodeEnum invalidKeyStatus() {
            return INVALID_PAUSE_KEY;
        }
    },
    /**
     * The freeze key.
     */
    FREEZE_KEY {
        @Override
        public boolean isPresentInUpdate(TokenUpdateTransactionBody update) {
            return update.hasFreezeKey();
        }

        @Override
        public boolean isPresentInitially(Token originalToken) {
            return originalToken.hasFreezeKey();
        }

        @Override
        public void setOn(final Token.Builder builder, TokenUpdateTransactionBody update) {
            builder.freezeKey(getNewKeyValue(update.freezeKey()));
        }

        @Override
        public Key getFromUpdate(TokenUpdateTransactionBody update) {
            return update.freezeKey();
        }

        @Override
        public Key getFromToken(Token originalToken) {
            return originalToken.freezeKey();
        }

        @Override
        public ResponseCodeEnum tokenHasNoKeyStatus() {
            return TOKEN_HAS_NO_FREEZE_KEY;
        }

        @Override
        public ResponseCodeEnum invalidKeyStatus() {
            return INVALID_FREEZE_KEY;
        }
    },
    /**
     * The KYC key.
     */
    KYC_KEY {
        @Override
        public boolean isPresentInUpdate(TokenUpdateTransactionBody update) {
            return update.hasKycKey();
        }

        @Override
        public boolean isPresentInitially(Token originalToken) {
            return originalToken.hasKycKey();
        }

        @Override
        public void setOn(final Token.Builder builder, TokenUpdateTransactionBody update) {
            builder.kycKey(getNewKeyValue(update.kycKey()));
        }

        @Override
        public Key getFromUpdate(TokenUpdateTransactionBody update) {
            return update.kycKey();
        }

        @Override
        public Key getFromToken(Token originalToken) {
            return originalToken.kycKey();
        }

        @Override
        public ResponseCodeEnum tokenHasNoKeyStatus() {
            return TOKEN_HAS_NO_KYC_KEY;
        }

        @Override
        public ResponseCodeEnum invalidKeyStatus() {
            return INVALID_KYC_KEY;
        }
    },
    /**
     * The metadata key.
     */
    METADATA_KEY {
        @Override
        public ResponseCodeEnum invalidKeyStatus() {
            return INVALID_METADATA_KEY;
        }

        @Override
        public boolean isPresentInUpdate(TokenUpdateTransactionBody update) {
            return update.hasMetadataKey();
        }

        @Override
        public boolean isPresentInitially(Token originalToken) {
            return originalToken.hasMetadataKey();
        }

        @Override
        public void setOn(final Token.Builder builder, TokenUpdateTransactionBody update) {
            builder.metadataKey(getNewKeyValue(update.metadataKey()));
        }

        @Override
        public Key getFromUpdate(TokenUpdateTransactionBody update) {
            return update.metadataKey();
        }

        @Override
        public Key getFromToken(Token originalToken) {
            return originalToken.metadataKey();
        }

        @Override
        public ResponseCodeEnum tokenHasNoKeyStatus() {
            return TOKEN_HAS_NO_METADATA_KEY;
        }
    };

    /**
     * Check if the key is present in the update transaction body.
     * @param update the update transaction body
     * @return true if the key is present in the update transaction body
     */
    public abstract boolean isPresentInUpdate(TokenUpdateTransactionBody update);

    /**
     * Check if the key is present in the original token.
     * @param originalToken the original token
     * @return true if the key is present in the original token and false otherwise
     */
    public abstract boolean isPresentInitially(Token originalToken);

    /**
     * Set the key on the token builder.
     * @param builder the token builder
     * @param update the update transaction body
     */
    public abstract void setOn(Token.Builder builder, TokenUpdateTransactionBody update);

    /**
     * Get the key from the update transaction body.
     * @param update the update transaction body
     * @return the key or null if the key is not present
     */
    public abstract Key getFromUpdate(TokenUpdateTransactionBody update);

    /**
     * Get the key from the original token.
     * @param originalToken the original token
     * @return the key or null if the key is not present
     */
    public abstract @Nullable Key getFromToken(Token originalToken);

    /**
     * Get the status code to be returned when the token has no key, based on the key type.
     * @return the status code
     */
    public abstract ResponseCodeEnum tokenHasNoKeyStatus();

    /**
     * Get the status code to be returned when the key is invalid, based on the key type.
     * @return the status code
     */
    public abstract ResponseCodeEnum invalidKeyStatus();

    /**
     * Check if the transaction body has immutable sentinel key used for key removals.
     * @param update the update transaction body
     * @return true if the transaction body has the sentinel key and false otherwise
     */
    public boolean containsKeyRemoval(@NonNull final TokenUpdateTransactionBody update) {
        if (isPresentInUpdate(update)) {
            return isKeyRemoval(getFromUpdate(update));
        }
        return false;
    }

    /**
     * Update the key on the token builder.
     * @param update the update transaction body
     * @param originalToken the original token
     * @param builder the token builder
     */
    public void updateKey(
            @NonNull final TokenUpdateTransactionBody update,
            @NonNull final Token originalToken,
            @NonNull final Token.Builder builder) {
        if (isPresentInUpdate(update)) {
            validateTrue(isPresentInitially(originalToken), tokenHasNoKeyStatus());
            setOn(builder, update);
        }
    }

    /**
     * Get the new key value. If the key is a sentinel key used for key removals, return null. Otherwise,
     * return the key.
     * @param newKey the new key
     * @return the new key value
     */
    private static Key getNewKeyValue(Key newKey) {
        return isKeyRemoval(newKey) ? null : newKey;
    }
}
