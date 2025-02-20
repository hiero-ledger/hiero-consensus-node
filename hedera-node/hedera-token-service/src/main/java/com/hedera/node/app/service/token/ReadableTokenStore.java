// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.Key.KeyOneOfType;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.state.token.Token;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Provides read-only methods for interacting with the underlying data storage mechanisms for
 * working with Tokens.
 */
public interface ReadableTokenStore {

    /**
     * Returns the token metadata needed for signing requirements.
     *
     * @param id token id being looked up
     * @return token's metadata
     */
    @Nullable
    TokenMetadata getTokenMeta(@NonNull TokenID id);

    /** Represents the metadata for a token. This should be deprecated and use Token instead in the FUTURE.
     * @param adminKey admin key of the token
     * @param kycKey kyc key of the token
     * @param wipeKey wipe key of the token
     * @param freezeKey freeze key of the token
     * @param supplyKey supply key of the token
     * @param feeScheduleKey fee schedule key of the token
     * @param pauseKey pause key of the token
     * @param symbol symbol of the token
     * @param hasRoyaltyWithFallback whether the token has royalty with fallback
     * @param treasuryAccountId treasury account id of the token
     * @param decimals decimals of the token
     */
    record TokenMetadata(
            @Nullable Key adminKey,
            @Nullable Key kycKey,
            @Nullable Key wipeKey,
            @Nullable Key freezeKey,
            @Nullable Key supplyKey,
            @Nullable Key feeScheduleKey,
            @Nullable Key pauseKey,
            @Nullable String symbol,
            boolean hasRoyaltyWithFallback,
            AccountID treasuryAccountId,
            int decimals) {
        /**
         * Returns whether the token has an admin key.
         * @return whether the token has an admin key
         */
        public boolean hasAdminKey() {
            return adminKey != null && !adminKey.key().kind().equals(KeyOneOfType.UNSET);
        }
        /**
         * Returns whether the token has a kyc key.
         * @return whether the token has a kyc key
         */
        public boolean hasKycKey() {
            return kycKey != null && !kycKey.key().kind().equals(KeyOneOfType.UNSET);
        }
        /**
         * Returns whether the token has a wipe key.
         * @return whether the token has a wipe key
         */
        public boolean hasWipeKey() {
            return wipeKey != null && !wipeKey.key().kind().equals(KeyOneOfType.UNSET);
        }
        /**
         * Returns whether the token has a freeze key.
         * @return whether the token has a freeze key
         */
        public boolean hasFreezeKey() {
            return freezeKey != null && !freezeKey.key().kind().equals(KeyOneOfType.UNSET);
        }
        /**
         * Returns whether the token has a supply key.
         * @return whether the token has a supply key
         */
        public boolean hasSupplyKey() {
            return supplyKey != null && !supplyKey.key().kind().equals(KeyOneOfType.UNSET);
        }
        /**
         * Returns whether the token has a fee schedule key.
         * @return whether the token has a fee schedule key
         */
        public boolean hasFeeScheduleKey() {
            return feeScheduleKey != null && !feeScheduleKey.key().kind().equals(KeyOneOfType.UNSET);
        }
        /**
         * Returns whether the token has a pause key.
         * @return whether the token has a pause key
         */
        public boolean hasPauseKey() {
            return pauseKey != null && !pauseKey.key().kind().equals(KeyOneOfType.UNSET);
        }
    }

    /**
     * Returns all the data for a token.
     *
     * @param id the token id to look up
     * @return the token
     */
    @Nullable
    Token get(@NonNull TokenID id);

    /**
     * Returns the number of tokens in the state.
     * @return the number of tokens in the state
     */
    long sizeOfState();

    /**
     * Warms the system by preloading a token into memory
     *
     * <p>The default implementation is empty because preloading data into memory is only used for some implementations.
     *
     * @param tokenId the token id
     */
    default void warm(@NonNull TokenID tokenId) {}
}
