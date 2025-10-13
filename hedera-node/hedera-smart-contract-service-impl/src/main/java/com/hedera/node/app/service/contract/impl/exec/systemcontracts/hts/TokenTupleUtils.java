// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts;

import static com.hedera.node.app.service.contract.impl.utils.ConstantUtils.ZERO_ACCOUNT_ID;
import static com.hedera.node.app.service.contract.impl.utils.ConstantUtils.ZERO_ADDRESS;
import static com.hedera.node.app.service.contract.impl.utils.ConstantUtils.ZERO_CONTRACT_ID;
import static com.hedera.node.app.service.contract.impl.utils.ConstantUtils.ZERO_FIXED_FEE;
import static com.hedera.node.app.service.contract.impl.utils.ConstantUtils.ZERO_FRACTION;
import static com.hedera.node.app.service.contract.impl.utils.ConstantUtils.ZERO_TOKEN_ID;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.headlongAddressOf;
import static java.util.Objects.requireNonNull;

import com.esaulpaugh.headlong.abi.Address;
import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Duration;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.token.Nft;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.token.TokenCreateTransactionBody;
import com.hedera.hapi.node.transaction.CustomFee;
import com.hedera.hapi.node.transaction.FixedFee;
import com.hedera.hapi.node.transaction.FractionalFee;
import com.hedera.hapi.node.transaction.RoyaltyFee;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hederahashgraph.api.proto.java.TokenSupplyType;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.util.List;

/**
 * Utility class with methods for assembling {@code Tuple} containing token information
 */
public class TokenTupleUtils {
    private TokenTupleUtils() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Returns a tuple of the {@code Expiry} struct
     * {@see
     * https://github.com/hashgraph/hedera-smart-contracts/blob/main/contracts/hts-precompile/IHederaTokenService.sol#L69
     * }
     *
     * @param token the token to get the expiry for
     * @return Tuple encoding of the Expiry
     */
    @NonNull
    public static Tuple expiryTupleFor(@NonNull final Token token) {
        return Tuple.of(
                token.expirationSecond(),
                headlongAddressOf(token.autoRenewAccountIdOrElse(ZERO_ACCOUNT_ID)),
                Math.max(0, token.autoRenewSeconds()));
    }

    @NonNull
    public static Tuple expiryTupleFor(@NonNull final TokenCreateTransactionBody token) {
        return Tuple.of(
                token.expiry().seconds(),
                headlongAddressOf(token.autoRenewAccountOrElse(ZERO_ACCOUNT_ID)),
                Math.max(0, token.autoRenewPeriodOrElse(Duration.DEFAULT).seconds()));
    }

    /**
     * Returns a tuple containing the response code, fixedFees, fractionalFees and the royaltyFees for the token
     *
     * @param token the token to get the fees for
     * @return Tuple encoding of the arrays of fixedFees, fractionalFees and royaltyFees
     */
    @NonNull
    public static Tuple feesTupleFor(final int responseCode, @NonNull final Token token) {
        final var fixedFees = fixedFeesTupleListFor(token);
        final var fractionalFees = fractionalFeesTupleListFor(token);
        final var royaltyFees = royaltyFeesTupleListFor(token);
        return Tuple.of(
                responseCode,
                fixedFeesTupleListFor(token).toArray(new Tuple[fixedFees.size()]),
                fractionalFeesTupleListFor(token).toArray(new Tuple[fractionalFees.size()]),
                royaltyFeesTupleListFor(token).toArray(new Tuple[royaltyFees.size()]));
    }

    /**
     * Returns a list of Tuples defined by the {@code FixedFee} structs
     * <br><a href="https://github.com/hashgraph/hedera-smart-contracts/blob/main/contracts/hts-precompile/IHederaTokenService.sol#L236">Link</a>
     * @param token the token to get the fixed fees for
     * @return Tuple encoding of the FixedFee
     */
    @SuppressWarnings("DataFlowIssue")
    @NonNull
    private static List<? extends Tuple> fixedFeesTupleListFor(@NonNull final Token token) {
        //
        assert token.customFees() != null;
        return token.customFees().stream()
                .filter(CustomFee::hasFixedFee)
                .map(fee -> Tuple.of(
                        fee.fixedFeeOrElse(FixedFee.DEFAULT).amount(),
                        headlongAddressOf(fee.fixedFee().denominatingTokenIdOrElse(ZERO_TOKEN_ID)),
                        !fee.fixedFeeOrElse(FixedFee.DEFAULT).hasDenominatingTokenId(),
                        false,
                        headlongAddressOf(fee.feeCollectorAccountIdOrElse(ZERO_ACCOUNT_ID))))
                .toList();
    }

    @SuppressWarnings("DataFlowIssue")
    @NonNull
    private static List<? extends Tuple> fixedFeesTupleListFor(@NonNull final TokenCreateTransactionBody token) {
        //
        assert token.customFees() != null;
        return token.customFees().stream()
                .filter(CustomFee::hasFixedFee)
                .map(fee -> Tuple.of(
                        fee.fixedFeeOrElse(FixedFee.DEFAULT).amount(),
                        headlongAddressOf(fee.fixedFee().denominatingTokenIdOrElse(ZERO_TOKEN_ID)),
                        !fee.fixedFeeOrElse(FixedFee.DEFAULT).hasDenominatingTokenId(),
                        false,
                        headlongAddressOf(fee.feeCollectorAccountIdOrElse(ZERO_ACCOUNT_ID))))
                .toList();
    }

    /**
     * Returns a list of Tuples defined by the {@code FractionalFee} struct
     * <br>{<a href="https://github.com/hashgraph/hedera-smart-contracts/blob/main/contracts/hts-precompile/IHederaTokenService.sol#L256">Link</a>
     * @param token the token to get the fractional fees for
     * @return Tuple encoding of the FractionalFee
     */
    @SuppressWarnings("DataFlowIssue")
    @NonNull
    private static List<? extends Tuple> fractionalFeesTupleListFor(@NonNull final Token token) {
        // array of struct FractionalFee { uint32 numerator; uint32 denominator; uint32 minimumAmount; uint32
        // maximumAmount; bool netOfTransfers; address feeCollector; }
        return token.customFees().stream()
                .filter(CustomFee::hasFractionalFee)
                .map(fee -> Tuple.of(
                        fee.fractionalFeeOrElse(FractionalFee.DEFAULT)
                                .fractionalAmountOrElse(ZERO_FRACTION)
                                .numerator(),
                        fee.fractionalFeeOrElse(FractionalFee.DEFAULT)
                                .fractionalAmountOrElse(ZERO_FRACTION)
                                .denominator(),
                        fee.fractionalFeeOrElse(FractionalFee.DEFAULT).minimumAmount(),
                        fee.fractionalFeeOrElse(FractionalFee.DEFAULT).maximumAmount(),
                        fee.fractionalFeeOrElse(FractionalFee.DEFAULT).netOfTransfers(),
                        headlongAddressOf(fee.feeCollectorAccountIdOrElse(ZERO_ACCOUNT_ID))))
                .toList();
    }

    @SuppressWarnings("DataFlowIssue")
    @NonNull
    private static List<? extends Tuple> fractionalFeesTupleListFor(@NonNull final TokenCreateTransactionBody token) {
        // array of struct FractionalFee { uint32 numerator; uint32 denominator; uint32 minimumAmount; uint32
        // maximumAmount; bool netOfTransfers; address feeCollector; }
        return token.customFees().stream()
                .filter(CustomFee::hasFractionalFee)
                .map(fee -> Tuple.of(
                        fee.fractionalFeeOrElse(FractionalFee.DEFAULT)
                                .fractionalAmountOrElse(ZERO_FRACTION)
                                .numerator(),
                        fee.fractionalFeeOrElse(FractionalFee.DEFAULT)
                                .fractionalAmountOrElse(ZERO_FRACTION)
                                .denominator(),
                        fee.fractionalFeeOrElse(FractionalFee.DEFAULT).minimumAmount(),
                        fee.fractionalFeeOrElse(FractionalFee.DEFAULT).maximumAmount(),
                        fee.fractionalFeeOrElse(FractionalFee.DEFAULT).netOfTransfers(),
                        headlongAddressOf(fee.feeCollectorAccountIdOrElse(ZERO_ACCOUNT_ID))))
                .toList();
    }

    /**
     * Returns a list of Tuples defined by the {@code RoyaltyFee} struct
     * <br><a href="https://github.com/hashgraph/hedera-smart-contracts/blob/main/contracts/hts-precompile/IHederaTokenService.sol#L279">Link</a>
     * @param token the token to get the royalty fees for
     * @return Tuple encoding of the RoyaltyFee
     */
    @SuppressWarnings("DataFlowIssue")
    @NonNull
    private static List<? extends Tuple> royaltyFeesTupleListFor(@NonNull final Token token) {
        // array of struct RoyaltyFee { uint32 numerator; uint32 denominator; uint32 amount; address tokenId; bool
        // useHbarsForPayment; address feeCollector; }
        return token.customFees().stream()
                .filter(CustomFee::hasRoyaltyFee)
                .map(fee -> {
                    final var hasFallbackDenominatingTokenId = fee.royaltyFeeOrElse(RoyaltyFee.DEFAULT)
                            .fallbackFeeOrElse(ZERO_FIXED_FEE)
                            .hasDenominatingTokenId();
                    final var denominatingTokenId = hasFallbackDenominatingTokenId
                            ? fee.royaltyFeeOrElse(RoyaltyFee.DEFAULT)
                                    .fallbackFee()
                                    .denominatingTokenId()
                            : ZERO_TOKEN_ID;
                    return Tuple.of(
                            fee.royaltyFeeOrElse(RoyaltyFee.DEFAULT)
                                    .exchangeValueFractionOrElse(ZERO_FRACTION)
                                    .numerator(),
                            fee.royaltyFeeOrElse(RoyaltyFee.DEFAULT)
                                    .exchangeValueFractionOrElse(ZERO_FRACTION)
                                    .denominator(),
                            fee.royaltyFeeOrElse(RoyaltyFee.DEFAULT)
                                    .fallbackFeeOrElse(ZERO_FIXED_FEE)
                                    .amount(),
                            headlongAddressOf(denominatingTokenId),
                            !hasFallbackDenominatingTokenId,
                            headlongAddressOf(fee.feeCollectorAccountIdOrElse(ZERO_ACCOUNT_ID)));
                })
                .toList();
    }

    @SuppressWarnings("DataFlowIssue")
    @NonNull
    private static List<? extends Tuple> royaltyFeesTupleListFor(@NonNull final TokenCreateTransactionBody token) {
        // array of struct RoyaltyFee { uint32 numerator; uint32 denominator; uint32 amount; address tokenId; bool
        // useHbarsForPayment; address feeCollector; }
        return token.customFees().stream()
                .filter(CustomFee::hasRoyaltyFee)
                .map(fee -> {
                    final var hasFallbackDenominatingTokenId = fee.royaltyFeeOrElse(RoyaltyFee.DEFAULT)
                            .fallbackFeeOrElse(ZERO_FIXED_FEE)
                            .hasDenominatingTokenId();
                    final var denominatingTokenId = hasFallbackDenominatingTokenId
                            ? fee.royaltyFeeOrElse(RoyaltyFee.DEFAULT)
                                    .fallbackFee()
                                    .denominatingTokenId()
                            : ZERO_TOKEN_ID;
                    return Tuple.of(
                            fee.royaltyFeeOrElse(RoyaltyFee.DEFAULT)
                                    .exchangeValueFractionOrElse(ZERO_FRACTION)
                                    .numerator(),
                            fee.royaltyFeeOrElse(RoyaltyFee.DEFAULT)
                                    .exchangeValueFractionOrElse(ZERO_FRACTION)
                                    .denominator(),
                            fee.royaltyFeeOrElse(RoyaltyFee.DEFAULT)
                                    .fallbackFeeOrElse(ZERO_FIXED_FEE)
                                    .amount(),
                            headlongAddressOf(denominatingTokenId),
                            !hasFallbackDenominatingTokenId,
                            headlongAddressOf(fee.feeCollectorAccountIdOrElse(ZERO_ACCOUNT_ID)));
                })
                .toList();
    }

    /**
     * Returns a list of Tuples defined by the {@code TokenInfo} struct
     * <br><a href="https://github.com/hashgraph/hedera-smart-contracts/blob/main/contracts/hts-precompile/IHederaTokenService.sol#L173">Link</a>
     * @param token the token to get the token info for
     * @return Tuple encoding of the TokenInfo
     */
    @NonNull
    public static Tuple tokenInfoTupleFor(@NonNull final Token token, @NonNull final String ledgerId, int version) {
        final var fixedFees = fixedFeesTupleListFor(token);
        final var fractionalFees = fractionalFeesTupleListFor(token);
        final var royaltyFees = royaltyFeesTupleListFor(token);

        final var hederaToken = version == 1 ? hederaTokenTupleFor(token) : hederaTokenTupleFor16c(token);

        return Tuple.from(
                hederaToken,
                token.totalSupply(),
                token.deleted(),
                token.accountsKycGrantedByDefault(),
                token.paused(),
                fixedFees.toArray(new Tuple[fixedFees.size()]),
                fractionalFees.toArray(new Tuple[fractionalFees.size()]),
                royaltyFees.toArray(new Tuple[royaltyFees.size()]),
                ledgerId);
    }

    @NonNull
    public static Tuple tokenInfoTupleFor(
            @NonNull final TokenCreateTransactionBody token, @NonNull final String ledgerId, int version) {
        final var fixedFees = fixedFeesTupleListFor(token);
        final var fractionalFees = fractionalFeesTupleListFor(token);
        final var royaltyFees = royaltyFeesTupleListFor(token);

        final var hederaToken = version == 1 ? hederaTokenTupleFor(token) : hederaTokenTupleFor16c(token);

        return Tuple.from(
                hederaToken,
                token.initialSupply(),
                false,
                false,
                false,
                fixedFees.toArray(new Tuple[fixedFees.size()]),
                fractionalFees.toArray(new Tuple[fractionalFees.size()]),
                royaltyFees.toArray(new Tuple[royaltyFees.size()]),
                ledgerId);
    }

    /**
     * Returns a tuple of the {@code FungibleTokenInfo} struct
     * <br><a href="https://github.com/hashgraph/hedera-smart-contracts/blob/main/contracts/hts-precompile/IHederaTokenService.sol#L203">Link</a>
     * @param token the token to get the fungible token info for
     * @return Tuple encoding of the FungibleTokenInfo
     */
    @NonNull
    public static Tuple fungibleTokenInfoTupleFor(
            @NonNull final Token token, @NonNull final String ledgerId, int version) {
        return Tuple.of(tokenInfoTupleFor(token, ledgerId, version), token.decimals());
    }

    @NonNull
    public static Tuple fungibleTokenInfoTupleFor(
            @NonNull final TokenCreateTransactionBody token, @NonNull final String ledgerId, int version) {
        return Tuple.of(tokenInfoTupleFor(token, ledgerId, version), token.decimals());
    }

    /**
     * Returns a tuple of the {@code NonFungibleTokenInfo} struct
     * <br><a href="https://github.com/hashgraph/hedera-smart-contracts/blob/main/contracts/hts-precompile/IHederaTokenService.sol#L212">Link</a>
     * @return Tuple encoding of the NonFungibleTokenInfo
     */
    @NonNull
    public static Tuple nftTokenInfoTupleFor(
            @NonNull final Token token,
            @NonNull final Nft nft,
            final long serialNumber,
            @NonNull final String ledgerId,
            @NonNull final HederaNativeOperations nativeOperations,
            int version) {
        requireNonNull(nft);
        requireNonNull(token);
        requireNonNull(ledgerId);
        requireNonNull(nativeOperations);

        final var nftMetaData = nft.metadata() != null ? nft.metadata().toByteArray() : Bytes.EMPTY.toByteArray();
        return Tuple.of(
                tokenInfoTupleFor(token, ledgerId, version),
                serialNumber,
                // The odd construct allowing a token to not have a treasury account set is to accommodate
                // Token.DEFAULT being passed into this method, which a few Call implementations do
                priorityAddressOf(nft.ownerIdOrElse(token.treasuryAccountIdOrElse(ZERO_ACCOUNT_ID)), nativeOperations),
                nft.mintTimeOrElse(new Timestamp(0, 0)).seconds(),
                nftMetaData,
                priorityAddressOf(nft.spenderIdOrElse(ZERO_ACCOUNT_ID), nativeOperations));
    }

    @NonNull
    public static Tuple nftTokenInfoTupleFor(
            @NonNull final TokenCreateTransactionBody token, @NonNull final String ledgerId, int version) {
        requireNonNull(token);
        requireNonNull(ledgerId);

        final var nftMetaData = token.metadata() != null ? token.metadata().toByteArray() : Bytes.EMPTY.toByteArray();
        return Tuple.of(
                tokenInfoTupleFor(token, ledgerId, version),
                0L,
                // The odd construct allowing a token to not have a treasury account set is to accommodate
                // Token.DEFAULT being passed into this method, which a few Call implementations do
                headlongAddressOf(token.treasuryOrElse(ZERO_ACCOUNT_ID)),
                new Timestamp(0, 0).seconds(),
                nftMetaData,
                headlongAddressOf(token.treasuryOrElse(ZERO_ACCOUNT_ID)));
    }

    private static Address priorityAddressOf(
            @NonNull final AccountID accountId, @NonNull final HederaNativeOperations nativeOperations) {
        requireNonNull(accountId);
        return (ZERO_ACCOUNT_ID == accountId)
                ? ZERO_ADDRESS
                : headlongAddressOf(requireNonNull(nativeOperations.getAccount(accountId)));
    }

    /**
     * Returns a tuple of the {@code TokenKey} struct
     * <br><a href="https://github.com/hashgraph/hedera-smart-contracts/blob/main/contracts/hts-precompile/IHederaTokenService.sol#L116">Link</a>
     * @param keyType the type of key
     * @param key the key for creating the tuple
     * @return Tuple encoding of the TokenKey
     */
    @NonNull
    public static Tuple typedKeyTupleFor(@NonNull final BigInteger keyType, @NonNull final Key key) {
        return Tuple.of(
                keyType,
                Tuple.of(
                        false,
                        headlongAddressOf(key.contractIDOrElse(ZERO_CONTRACT_ID)),
                        key.ed25519OrElse(Bytes.EMPTY).toByteArray(),
                        key.ecdsaSecp256k1OrElse(Bytes.EMPTY).toByteArray(),
                        headlongAddressOf(key.delegatableContractIdOrElse(ZERO_CONTRACT_ID))));
    }

    /**
     * Returns a tuple of the {@code HederaToken} struct
     * <br><a href="https://github.com/hashgraph/hedera-smart-contracts/blob/main/contracts/hts-precompile/IHederaTokenService.sol#L136">Link</a>
     * @param token the token for creating the hederaToken tuple
     * @return Tuple encoding of the HederaToken
     */
    @NonNull
    private static Tuple hederaTokenTupleFor(@NonNull final Token token) {
        return Tuple.from(
                token.name(),
                token.symbol(),
                headlongAddressOf(token.treasuryAccountIdOrElse(ZERO_ACCOUNT_ID)),
                token.memo(),
                token.supplyType().protoOrdinal() == TokenSupplyType.FINITE_VALUE,
                token.maxSupply(),
                token.accountsFrozenByDefault(),
                prepareKeyList(token, false),
                expiryTupleFor(token));
    }

    @NonNull
    private static Tuple hederaTokenTupleFor(@NonNull final TokenCreateTransactionBody token) {
        return Tuple.from(
                token.name(),
                token.symbol(),
                headlongAddressOf(token.treasuryOrElse(ZERO_ACCOUNT_ID)),
                token.memo(),
                token.supplyType().protoOrdinal() == TokenSupplyType.FINITE_VALUE,
                token.maxSupply(),
                token.freezeDefault(),
                prepareKeyList(token, false),
                expiryTupleFor(token));
    }

    private static Tuple[] prepareKeyList(@NonNull final Token token, final boolean isV2) {
        final var keyList = new java.util.ArrayList<>(List.of(
                typedKeyTupleFor(TokenKeyType.ADMIN_KEY.bigIntegerValue(), token.adminKeyOrElse(Key.DEFAULT)),
                typedKeyTupleFor(TokenKeyType.KYC_KEY.bigIntegerValue(), token.kycKeyOrElse(Key.DEFAULT)),
                typedKeyTupleFor(TokenKeyType.FREEZE_KEY.bigIntegerValue(), token.freezeKeyOrElse(Key.DEFAULT)),
                typedKeyTupleFor(TokenKeyType.WIPE_KEY.bigIntegerValue(), token.wipeKeyOrElse(Key.DEFAULT)),
                typedKeyTupleFor(TokenKeyType.SUPPLY_KEY.bigIntegerValue(), token.supplyKeyOrElse(Key.DEFAULT)),
                typedKeyTupleFor(
                        TokenKeyType.FEE_SCHEDULE_KEY.bigIntegerValue(), token.feeScheduleKeyOrElse(Key.DEFAULT)),
                typedKeyTupleFor(TokenKeyType.PAUSE_KEY.bigIntegerValue(), token.pauseKeyOrElse(Key.DEFAULT))));
        if (isV2) {
            keyList.add(typedKeyTupleFor(
                    TokenKeyType.METADATA_KEY.bigIntegerValue(), token.metadataKeyOrElse(Key.DEFAULT)));
        }
        return keyList.toArray(Tuple[]::new);
    }

    private static Tuple[] prepareKeyList(@NonNull final TokenCreateTransactionBody token, final boolean isV2) {
        final var keyList = new java.util.ArrayList<>(List.of(
                typedKeyTupleFor(TokenKeyType.ADMIN_KEY.bigIntegerValue(), token.adminKeyOrElse(Key.DEFAULT)),
                typedKeyTupleFor(TokenKeyType.KYC_KEY.bigIntegerValue(), token.kycKeyOrElse(Key.DEFAULT)),
                typedKeyTupleFor(TokenKeyType.FREEZE_KEY.bigIntegerValue(), token.freezeKeyOrElse(Key.DEFAULT)),
                typedKeyTupleFor(TokenKeyType.WIPE_KEY.bigIntegerValue(), token.wipeKeyOrElse(Key.DEFAULT)),
                typedKeyTupleFor(TokenKeyType.SUPPLY_KEY.bigIntegerValue(), token.supplyKeyOrElse(Key.DEFAULT)),
                typedKeyTupleFor(
                        TokenKeyType.FEE_SCHEDULE_KEY.bigIntegerValue(), token.feeScheduleKeyOrElse(Key.DEFAULT)),
                typedKeyTupleFor(TokenKeyType.PAUSE_KEY.bigIntegerValue(), token.pauseKeyOrElse(Key.DEFAULT))));
        if (isV2) {
            keyList.add(typedKeyTupleFor(
                    TokenKeyType.METADATA_KEY.bigIntegerValue(), token.metadataKeyOrElse(Key.DEFAULT)));
        }
        return keyList.toArray(Tuple[]::new);
    }

    @NonNull
    private static Tuple hederaTokenTupleFor16c(@NonNull final Token token) {
        final var tokenMetaData =
                token.metadata().length() > 0 ? token.metadata().toByteArray() : Bytes.EMPTY.toByteArray();
        return Tuple.from(
                token.name(),
                token.symbol(),
                headlongAddressOf(token.treasuryAccountIdOrElse(ZERO_ACCOUNT_ID)),
                token.memo(),
                token.supplyType().protoOrdinal() == TokenSupplyType.FINITE_VALUE,
                token.maxSupply(),
                token.accountsFrozenByDefault(),
                prepareKeyList(token, true),
                expiryTupleFor(token),
                tokenMetaData);
    }

    @NonNull
    private static Tuple hederaTokenTupleFor16c(@NonNull final TokenCreateTransactionBody token) {
        final var tokenMetaData =
                token.metadata().length() > 0 ? token.metadata().toByteArray() : Bytes.EMPTY.toByteArray();
        return Tuple.from(
                token.name(),
                token.symbol(),
                headlongAddressOf(token.treasuryOrElse(ZERO_ACCOUNT_ID)),
                token.memo(),
                token.supplyType().protoOrdinal() == TokenSupplyType.FINITE_VALUE,
                token.maxSupply(),
                token.freezeDefault(),
                prepareKeyList(token, true),
                expiryTupleFor(token),
                tokenMetaData);
    }

    public enum TokenKeyType {
        ADMIN_KEY(1),
        KYC_KEY(2),
        FREEZE_KEY(4),
        WIPE_KEY(8),
        SUPPLY_KEY(16),
        FEE_SCHEDULE_KEY(32),
        PAUSE_KEY(64),
        METADATA_KEY(128);

        private final int value;

        TokenKeyType(final int value) {
            this.value = value;
        }

        public int value() {
            return value;
        }

        public BigInteger bigIntegerValue() {
            return BigInteger.valueOf(value);
        }
    }
}
