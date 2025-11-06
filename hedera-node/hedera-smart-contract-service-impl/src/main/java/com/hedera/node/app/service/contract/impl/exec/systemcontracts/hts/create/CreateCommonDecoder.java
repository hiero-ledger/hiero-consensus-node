// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.create;

import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.asNumericContractId;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Duration;
import com.hedera.hapi.node.token.TokenCreateTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.AddressIdConverter;
import com.hedera.node.app.service.contract.impl.exec.utils.KeyValueWrapper;
import com.hedera.node.app.service.contract.impl.exec.utils.TokenCreateWrapper;
import com.hedera.node.app.service.contract.impl.exec.utils.TokenCreateWrapper.FixedFeeWrapper;
import com.hedera.node.app.service.contract.impl.exec.utils.TokenCreateWrapper.FractionalFeeWrapper;
import com.hedera.node.app.service.contract.impl.exec.utils.TokenCreateWrapper.RoyaltyFeeWrapper;
import com.hedera.node.app.service.contract.impl.exec.utils.TokenExpiryWrapper;
import com.hedera.node.app.service.contract.impl.exec.utils.TokenKeyWrapper;
import com.hedera.node.app.service.contract.impl.utils.ConversionUtils;
import com.hedera.node.app.service.entityid.EntityIdFactory;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

public class CreateCommonDecoder {

    // below values correspond to  tuples' indexes
    protected static final int HEDERA_TOKEN = 0;
    protected static final int INIT_SUPPLY = 1;
    protected static final int DECIMALS = 2;
    protected static final int FIXED_FEE = 3;
    protected static final int FRACTIONAL_FEE = 4;
    protected static final int NFT_FIXED_FEE = 1;
    protected static final int NFT_ROYALTY_FEE = 2;
    protected static final int METADATA = 9;

    /**
     * @param fixedFeesTuples the fixed fee tuple
     * @param addressIdConverter the address ID converter for this call
     * @return list of {@link FixedFeeWrapper}
     */
    protected List<FixedFeeWrapper> decodeFixedFees(
            @NonNull final Tuple[] fixedFeesTuples,
            @NonNull final AddressIdConverter addressIdConverter,
            @NonNull final EntityIdFactory entityIdFactory) {

        // FixedFee
        final int AMOUNT = 0;
        final int TOKEN_ID = 1;
        final int USE_HBARS_FOR_PAYMENTS = 2;
        final int USE_CURRENT_TOKEN_FOR_PAYMENT = 3;
        final int FEE_COLLECTOR = 4;

        final List<FixedFeeWrapper> fixedFees = new ArrayList<>(fixedFeesTuples.length);
        for (final var fixedFeeTuple : fixedFeesTuples) {
            final var amount = (long) fixedFeeTuple.get(AMOUNT);
            final var tokenId = ConversionUtils.asTokenId(entityIdFactory, fixedFeeTuple.get(TOKEN_ID));
            final var useHbarsForPayment = (Boolean) fixedFeeTuple.get(USE_HBARS_FOR_PAYMENTS);
            final var useCurrentTokenForPayment = (Boolean) fixedFeeTuple.get(USE_CURRENT_TOKEN_FOR_PAYMENT);
            final var feeCollector = addressIdConverter.convert(fixedFeeTuple.get(FEE_COLLECTOR));
            fixedFees.add(new FixedFeeWrapper(
                    amount,
                    tokenId.tokenNum() != 0 ? tokenId : null,
                    useHbarsForPayment,
                    useCurrentTokenForPayment,
                    feeCollector.accountNum() != 0 ? feeCollector : null));
        }
        return fixedFees;
    }

    /**
     * @param fractionalFeesTuples the fixed fee tuple
     * @param addressIdConverter the address ID converter for this call
     * @return list of {@link FractionalFeeWrapper}
     */
    protected List<FractionalFeeWrapper> decodeFractionalFees(
            @NonNull final Tuple[] fractionalFeesTuples, @NonNull final AddressIdConverter addressIdConverter) {

        // FractionalFee
        final int NUMERATOR = 0;
        final int DENOMINATOR = 1;
        final int MINIMUM_AMOUNT = 2;
        final int MAXIMUM_AMOUNT = 3;
        final int NET_OF_TRANSFERS = 4;
        final int FEE_COLLECTOR = 5;

        final List<FractionalFeeWrapper> fractionalFees = new ArrayList<>(fractionalFeesTuples.length);
        for (final var fractionalFeeTuple : fractionalFeesTuples) {
            final var numerator = (long) fractionalFeeTuple.get(NUMERATOR);
            final var denominator = (long) fractionalFeeTuple.get(DENOMINATOR);
            final var minimumAmount = (long) fractionalFeeTuple.get(MINIMUM_AMOUNT);
            final var maximumAmount = (long) fractionalFeeTuple.get(MAXIMUM_AMOUNT);
            final var netOfTransfers = (Boolean) fractionalFeeTuple.get(NET_OF_TRANSFERS);
            final var feeCollector = addressIdConverter.convert(fractionalFeeTuple.get(FEE_COLLECTOR));
            fractionalFees.add(new FractionalFeeWrapper(
                    numerator,
                    denominator,
                    minimumAmount,
                    maximumAmount,
                    netOfTransfers,
                    feeCollector.accountNum() != 0 ? feeCollector : null));
        }
        return fractionalFees;
    }

    /**
     * @param royaltyFeesTuples the fixed fee tuple
     * @param addressIdConverter the address ID converter for this call
     * @return list of {@link RoyaltyFeeWrapper}
     */
    protected List<RoyaltyFeeWrapper> decodeRoyaltyFees(
            @NonNull final Tuple[] royaltyFeesTuples,
            @NonNull final AddressIdConverter addressIdConverter,
            @NonNull final EntityIdFactory entityIdFactory) {

        // RoyaltyFee
        final int NUMERATOR = 0;
        final int DENOMINATOR = 1;
        final int FIXED_FEE_AMOUNT = 2;
        final int FIXED_FEE_TOKEN_ID = 3;
        final int FIXED_FEE_USE_HBARS = 4;

        final List<RoyaltyFeeWrapper> decodedRoyaltyFees = new ArrayList<>(royaltyFeesTuples.length);
        for (final var royaltyFeeTuple : royaltyFeesTuples) {
            final var numerator = (long) royaltyFeeTuple.get(NUMERATOR);
            final var denominator = (long) royaltyFeeTuple.get(DENOMINATOR);

            // When at least 1 of the following 3 values is different from its default value,
            // we treat it as though the user has tried to specify a fallbackFixedFee
            final var fixedFeeAmount = (long) royaltyFeeTuple.get(FIXED_FEE_AMOUNT);
            final var fixedFeeTokenId =
                    ConversionUtils.asTokenId(entityIdFactory, royaltyFeeTuple.get(FIXED_FEE_TOKEN_ID));
            final var fixedFeeUseHbars = (Boolean) royaltyFeeTuple.get(FIXED_FEE_USE_HBARS);
            TokenCreateWrapper.FixedFeeWrapper fixedFee = null;
            if (fixedFeeAmount != 0 || fixedFeeTokenId.tokenNum() != 0 || Boolean.TRUE.equals(fixedFeeUseHbars)) {
                fixedFee = new TokenCreateWrapper.FixedFeeWrapper(
                        fixedFeeAmount,
                        fixedFeeTokenId.tokenNum() != 0 ? fixedFeeTokenId : null,
                        fixedFeeUseHbars,
                        false,
                        null);
            }

            final var feeCollector = addressIdConverter.convert(royaltyFeeTuple.get(5));
            decodedRoyaltyFees.add(new RoyaltyFeeWrapper(
                    numerator, denominator, fixedFee, feeCollector.accountNum() != 0 ? feeCollector : null));
        }
        return decodedRoyaltyFees;
    }

    protected TokenCreateWrapper getTokenCreateWrapper(
            @NonNull final Tuple tokenCreateStruct,
            final boolean isFungible,
            final long initSupply,
            final int decimals,
            @NonNull final AccountID senderId,
            @NonNull final HederaNativeOperations nativeOperations,
            @NonNull final AddressIdConverter addressIdConverter) {
        // HederaToken
        final int TOKEN_NAME = 0;
        final int TOKEN_SYMBOL = 1;
        final int TOKEN_TREASURY = 2;
        final int MEMO = 3;
        final int SUPPLY_TYPE = 4;
        final int MAX_SUPPLY = 5;
        final int FREEZE_DEFAULT = 6;
        final int TOKEN_KEYS = 7;
        final int TOKEN_EXPIRY = 8;

        final var tokenName = (String) tokenCreateStruct.get(TOKEN_NAME);
        final var tokenSymbol = (String) tokenCreateStruct.get(TOKEN_SYMBOL);
        final var tokenTreasury = addressIdConverter.convert(tokenCreateStruct.get(TOKEN_TREASURY));
        final var memo = (String) tokenCreateStruct.get(MEMO);
        final var isSupplyTypeFinite = (Boolean) tokenCreateStruct.get(SUPPLY_TYPE);
        final var maxSupply = (long) tokenCreateStruct.get(MAX_SUPPLY);
        final var isFreezeDefault = (Boolean) tokenCreateStruct.get(FREEZE_DEFAULT);
        final var tokenKeys = decodeTokenKeys(tokenCreateStruct.get(TOKEN_KEYS), addressIdConverter, nativeOperations);
        final var tokenExpiry = decodeTokenExpiry(tokenCreateStruct.get(TOKEN_EXPIRY), addressIdConverter);

        final var tokenCreateWrapper = new TokenCreateWrapper(
                isFungible,
                tokenName,
                tokenSymbol,
                tokenTreasury.accountNumOrElse(0L) != 0 ? tokenTreasury : null,
                memo,
                isSupplyTypeFinite,
                initSupply,
                decimals,
                maxSupply,
                isFreezeDefault,
                tokenKeys,
                tokenExpiry);
        tokenCreateWrapper.setAllInheritedKeysTo(nativeOperations.getAccountKey(senderId));
        return tokenCreateWrapper;
    }

    protected List<TokenKeyWrapper> decodeTokenKeys(
            @NonNull final Tuple[] tokenKeysTuples,
            @NonNull final AddressIdConverter addressIdConverter,
            @NonNull final HederaNativeOperations nativeOperations) {

        // TokenKey
        final int KEY_TYPE = 0;
        final int KEY_VALUE_TYPE = 1;
        // KeyValue
        final int INHERIT_ACCOUNT_KEY = 0;
        final int CONTRACT_ID = 1;
        final int ED25519 = 2;
        final int ECDSA_SECP_256K1 = 3;
        final int DELEGATABLE_CONTRACT_ID = 4;

        final List<TokenKeyWrapper> tokenKeys = new ArrayList<>(tokenKeysTuples.length);
        for (final var tokenKeyTuple : tokenKeysTuples) {
            final var keyType = ((BigInteger) tokenKeyTuple.get(KEY_TYPE)).intValue();
            final Tuple keyValueTuple = tokenKeyTuple.get(KEY_VALUE_TYPE);
            final var inheritAccountKey = (Boolean) keyValueTuple.get(INHERIT_ACCOUNT_KEY);
            final var contractId = asNumericContractId(
                    nativeOperations.entityIdFactory(), addressIdConverter.convert(keyValueTuple.get(CONTRACT_ID)));
            final var ed25519 = (byte[]) keyValueTuple.get(ED25519);
            final var ecdsaSecp256K1 = (byte[]) keyValueTuple.get(ECDSA_SECP_256K1);
            final var delegatableContractId = asNumericContractId(
                    nativeOperations.entityIdFactory(),
                    addressIdConverter.convert(keyValueTuple.get(DELEGATABLE_CONTRACT_ID)));

            tokenKeys.add(new TokenKeyWrapper(
                    keyType,
                    new KeyValueWrapper(
                            inheritAccountKey,
                            contractId.contractNum() != 0 ? contractId : null,
                            ed25519,
                            ecdsaSecp256K1,
                            delegatableContractId.contractNum() != 0 ? delegatableContractId : null)));
        }

        return tokenKeys;
    }

    protected TokenExpiryWrapper decodeTokenExpiry(
            @NonNull final Tuple expiryTuple, @NonNull final AddressIdConverter addressIdConverter) {

        // Expiry
        final int SECOND = 0;
        final int AUTO_RENEW_ACCOUNT = 1;
        final int AUTO_RENEW_PERIOD = 2;

        final var second = (long) expiryTuple.get(SECOND);
        final var autoRenewAccount = addressIdConverter.convert(expiryTuple.get(AUTO_RENEW_ACCOUNT));
        final var autoRenewPeriod = Duration.newBuilder()
                .seconds(expiryTuple.get(AUTO_RENEW_PERIOD))
                .build();
        return new TokenExpiryWrapper(
                second, autoRenewAccount.accountNum() == 0 ? null : autoRenewAccount, autoRenewPeriod);
    }

    protected TransactionBody bodyOf(@NonNull final TokenCreateTransactionBody.Builder tokenCreate) {
        return TransactionBody.newBuilder().tokenCreation(tokenCreate).build();
    }
}
