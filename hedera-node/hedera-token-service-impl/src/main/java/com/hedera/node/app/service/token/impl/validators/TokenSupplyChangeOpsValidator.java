// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.validators;

import static com.hedera.hapi.node.base.ResponseCodeEnum.BATCH_SIZE_LIMIT_EXCEEDED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_NFT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.METADATA_TOO_LONG;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateFalsePreCheck;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateTruePreCheck;

import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.config.data.TokensConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.function.ToIntFunction;
import javax.inject.Inject;

/**
 * This class contains validations to be done in handle for Token Mint and
 * Token Burn operations in handle.
 */
public class TokenSupplyChangeOpsValidator {
    /**
     * Default constructor for injection.
     */
    @Inject
    public TokenSupplyChangeOpsValidator() {
        // Dagger
    }

    /**
     * Validate the transaction data for a token mint operation.
     *
     * @param fungibleCount the number of fungible tokens to mint
     * @param metaDataList the list of metadata for the NFTs to mint
     * @param tokensConfig the tokens configuration
     * @throws HandleException if the transaction data is invalid
     */
    public void validateMint(
            final long fungibleCount, final List<Bytes> metaDataList, final TokensConfig tokensConfig) {
        final var numNfts = metaDataList.size();
        validateCommon(fungibleCount, numNfts, TokensConfig::nftsMaxBatchSizeMint, tokensConfig);

        final var maxNftMetadataBytes = tokensConfig.nftsMaxMetadataBytes();
        if (fungibleCount <= 0 && numNfts > 0) {
            validateMetaData(metaDataList, maxNftMetadataBytes);
        }
    }

    /**
     * Validate the transaction data for a token mint operation.
     *
     * @param fungibleCount the number of fungible tokens to burn
     * @param nftSerialNums the list of NFT serial numbers to burn
     * @param tokensConfig  the tokens configuration
     * @throws HandleException if the transaction data is invalid
     */
    public void validateBurn(
            final long fungibleCount,
            @NonNull final List<Long> nftSerialNums,
            @NonNull final TokensConfig tokensConfig) {
        validateCommon(fungibleCount, nftSerialNums.size(), TokensConfig::nftsMaxBatchSizeBurn, tokensConfig);
    }

    /**
     * Checks that the transaction input data for a token operation is valid, specifically for operations
     * that change the supply of a token (i.e. a token's "instances").
     *
     * <p>
     * This method is static, so we can call it from handler pure checks methods without relying on any object instance
     *
     * @param fungibleAmount the amount of fungible tokens to burn
     * @param serialNums the list of NFT serial numbers to burn
     * @param hasToken whether the transaction body has a token ID
     * @param invalidAmountResponseCode the response code to throw if the {@code fungibleAmount} param is invalid
     * @throws PreCheckException if the transaction data is invalid
     */
    public static void verifyTokenInstanceAmounts(
            final long fungibleAmount,
            final @NonNull List<Long> serialNums,
            final boolean hasToken,
            @NonNull final ResponseCodeEnum invalidAmountResponseCode)
            throws PreCheckException {
        validateTruePreCheck(hasToken, INVALID_TOKEN_ID);

        // If a positive fungible fungibleAmount is present, the NFT serial numbers must be empty
        validateFalsePreCheck(fungibleAmount > 0 && !serialNums.isEmpty(), INVALID_TRANSACTION_BODY);

        // The fungible amount must not be negative, regardless of use case
        validateFalsePreCheck(fungibleAmount < 0, invalidAmountResponseCode);

        // Validate the NFT serial numbers
        if (fungibleAmount < 1 && !serialNums.isEmpty()) {
            for (final var serialNumber : serialNums) {
                validateTruePreCheck(serialNumber > 0, INVALID_NFT_ID);
            }
        }
    }

    /**
     * Validate the transaction data for a token mint operation.
     *
     * @param fungibleCount the number of fungible tokens to wipe
     * @param nftSerialNums the list of NFT serial numbers to wipe
     * @param tokensConfig the tokens configuration
     * @throws HandleException if the transaction data is invalid
     */
    public void validateWipe(
            final long fungibleCount,
            @NonNull final List<Long> nftSerialNums,
            @NonNull final TokensConfig tokensConfig) {
        validateCommon(fungibleCount, nftSerialNums.size(), TokensConfig::nftsMaxBatchSizeWipe, tokensConfig);
    }

    /**
     * Perform common validation checks for token operations mint, wipe, and burn given the attributes
     * of the transaction.
     *
     * @param fungibleCount The number of fungible common token to mint/wipe/burn.
     * @param nftCount the number of NFTs the operation will be performed on.
     * @param batchSizeGetter The function to get the corresponding batch size for the token operation.
     */
    private void validateCommon(
            final long fungibleCount,
            final int nftCount,
            @NonNull final ToIntFunction<TokensConfig> batchSizeGetter,
            final TokensConfig tokensConfig) {
        // Get needed configurations
        final var maxNftBatchOpSize = batchSizeGetter.applyAsInt(tokensConfig);
        // Validate the NFT count and fungible count are valid
        validateCounts(nftCount, fungibleCount, maxNftBatchOpSize);
    }

    /**
     * Validate the fungible amount and metadata size for a token mint or burn operation.
     * @param nftCount  The number of nfts to mint/burn.
     * @param fungibleCount The amount of fungible common token to mint/burn.
     * @param maxBatchSize The max batch size for the nft operation (based on config).
     */
    private void validateCounts(final int nftCount, final long fungibleCount, final long maxBatchSize) {
        if (fungibleCount <= 0 && nftCount > 0) {
            validateTrue(nftCount <= maxBatchSize, BATCH_SIZE_LIMIT_EXCEEDED);
        }
    }

    /**
     * Validate the metadata size for token operations mint.
     * @param metaDataList The metadata list of the nfts to mint.
     * @param maxNftMetadataBytes The max metadata size for nft mint based on config.
     */
    private void validateMetaData(final List<Bytes> metaDataList, final int maxNftMetadataBytes) {
        for (var bytes : metaDataList) {
            validateTrue(bytes.toByteArray().length <= maxNftMetadataBytes, METADATA_TOO_LONG);
        }
    }
}
