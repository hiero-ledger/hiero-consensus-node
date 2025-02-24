// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.validators;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ALLOWANCE_OWNER_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_NFT_SERIAL_NUMBER;
import static com.hedera.hapi.node.base.ResponseCodeEnum.MAX_ALLOWANCES_EXCEEDED;
import static com.hedera.node.app.service.token.impl.util.TokenHandlerHelper.AccountIDType.NOT_ALIASED_ID;
import static com.hedera.node.app.spi.workflows.HandleException.validateFalse;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.Nft;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.token.NftAllowance;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.ReadableNftStore;
import com.hedera.node.app.service.token.impl.util.TokenHandlerHelper;
import com.hedera.node.app.spi.validation.ExpiryValidator;
import com.hedera.node.config.data.HederaConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.HashSet;
import java.util.List;
import javax.inject.Inject;

/**
 * Provides validation for allowances.
 */
public class AllowanceValidator {
    /**
     * Default constructor for Dagger injection.
     */
    @Inject
    public AllowanceValidator() {
        // Dagger
    }

    /**
     * Validates the total number of allowances in a transaction. The total number of allowances
     * should not exceed the maximum limit. This limit includes the total number of crypto
     * allowances, total number of token allowances and total number of approvedForAll Nft allowances
     * on owner account.
     * @param totalAllowances total number of allowances in the transaction
     * @param hederaConfig the Hedera configuration
     */
    protected void validateTotalAllowancesPerTxn(final int totalAllowances, @NonNull final HederaConfig hederaConfig) {
        validateFalse(totalAllowances > hederaConfig.allowancesMaxTransactionLimit(), MAX_ALLOWANCES_EXCEEDED);
    }

    /**
     * Validates the serial numbers in the NftAllowance. The serial numbers should be valid and
     * should exist in the NftStore.
     * @param serialNums list of serial numbers
     * @param tokenId token id
     * @param nftStore nft store
     */
    protected void validateSerialNums(
            final List<Long> serialNums, final TokenID tokenId, final ReadableNftStore nftStore) {
        final var serialsSet = new HashSet<>(serialNums);
        for (final var serial : serialsSet) {
            validateTrue(serial > 0, INVALID_TOKEN_NFT_SERIAL_NUMBER);
            final var nft = nftStore.get(tokenId, serial);
            validateTrue(nft != null, INVALID_TOKEN_NFT_SERIAL_NUMBER);
        }
    }

    /* ------------------------ Helper methods needed for allowances validation ------------------------ */
    /**
     * Aggregate total serial numbers in CryptoApproveAllowance transaction.
     * Each serial number in an {@code NftAllowance} is considered as an allowance.
     *
     * @param nftAllowances a list of NFT individual allowances
     * @return the number of mentioned serial numbers
     */
    public static int aggregateApproveNftAllowances(final List<NftAllowance> nftAllowances) {
        int nftAllowancesTotal = 0;
        for (final var allowances : nftAllowances) {
            // each serial is counted as an allowance
            if (!allowances.serialNumbers().isEmpty()) {
                nftAllowancesTotal += allowances.serialNumbers().size();
            } else {
                nftAllowancesTotal++;
            }
        }
        return nftAllowancesTotal;
    }

    /**
     * Checks if the total allowances of an account will exceed the limit after applying this
     * transaction. This limit doesn't include number of serials for nfts, since they are not stored
     * on account. The limit includes number of crypto allowances, number of fungible token
     * allowances and number of approvedForAll Nft allowances on owner account
     *
     * @param owner The Account to validate the allowances limit on.
     * @param allowanceMaxAccountLimit maximum number of allowances an Account can have.
     */
    public static void validateAllowanceLimit(final Account owner, final int allowanceMaxAccountLimit) {
        final var totalAllowances = owner.cryptoAllowances().size()
                + owner.tokenAllowances().size()
                + owner.approveForAllNftAllowances().size();
        validateFalse(totalAllowances > allowanceMaxAccountLimit, MAX_ALLOWANCES_EXCEEDED);
    }

    /**
     * Checks the owner of token is treasury or the owner id given in allowance. If not, considers
     * as an invalid owner and returns false.
     *
     * @param nft given nft
     * @param ownerID owner given in allowance
     * @param token token for which nft belongs to
     * @return whether the owner is valid
     */
    public static boolean isValidOwner(
            @NonNull final Nft nft, @NonNull final AccountID ownerID, @NonNull final Token token) {
        requireNonNull(nft);
        requireNonNull(ownerID);
        requireNonNull(token);
        if (nft.hasOwnerId()) {
            return nft.ownerId().equals(ownerID);
        } else {
            return ownerID.equals(token.treasuryAccountId());
        }
    }

    /**
     * Returns owner account to be considered for the allowance changes. If the owner is missing in
     * allowance, considers payer of the transaction as the owner. This is same for
     * CryptoApproveAllowance and CryptoDeleteAllowance transaction. Looks at entitiesChanged map
     * before fetching from accountStore for performance.
     *
     * @param owner given owner
     * @param payer given payer for the transaction
     * @param accountStore account store
     * @param expiryValidator expiry validator
     * @return owner account
     */
    public static Account getEffectiveOwner(
            @Nullable final AccountID owner,
            @NonNull final Account payer,
            @NonNull final ReadableAccountStore accountStore,
            @NonNull final ExpiryValidator expiryValidator) {
        if (owner == null || owner.accountNumOrElse(0L) == 0L || owner.equals(payer.accountId())) {
            return payer;
        } else {
            // If owner is in modifications get the modified account from state
            return TokenHandlerHelper.getIfUsable(
                    owner,
                    accountStore,
                    expiryValidator,
                    INVALID_ALLOWANCE_OWNER_ID,
                    INVALID_ALLOWANCE_OWNER_ID,
                    NOT_ALIASED_ID);
        }
    }
}
