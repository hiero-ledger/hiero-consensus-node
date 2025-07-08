// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.create.address_0x167;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.create.CreateSyntheticTxnFactory.createToken;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.AddressIdConverter;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.create.CreateCommonDecoder;
import com.hedera.node.app.service.contract.impl.exec.utils.TokenCreateWrapper;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.math.BigInteger;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Provides help in decoding an {@link HtsCallAttempt} representing a create call into
 * a synthetic {@link TransactionBody}.
 */
@Singleton
public class CreateDecoder extends CreateCommonDecoder {

    /**
     * Default constructor for injection.
     */
    @Inject
    public CreateDecoder() {
        // Dagger2
    }

    /**
     * Decodes a call to {@link CreateTranslator#CREATE_FUNGIBLE_TOKEN_V1} into a synthetic {@link TransactionBody}.
     *
     * @param encoded the encoded call
     * @param senderId the id of the sender account
     * @param nativeOperations the Hedera native operation
     * @param addressIdConverter the address ID converter for this call
     * @return the synthetic transaction body
     */
    public @Nullable TransactionBody decodeCreateFungibleTokenV1(
            @NonNull final byte[] encoded,
            @NonNull final AccountID senderId,
            @NonNull final HederaNativeOperations nativeOperations,
            @NonNull final AddressIdConverter addressIdConverter) {
        final var call = CreateTranslator.CREATE_FUNGIBLE_TOKEN_V1.decodeCall(encoded);
        final var hederaToken = (Tuple) call.get(HEDERA_TOKEN);
        final var initSupply = ((BigInteger) call.get(INIT_SUPPLY)).longValueExact();
        final var decimals = ((BigInteger) call.get(DECIMALS)).intValueExact();
        final var tokenCreateWrapper = getTokenCreateWrapper(
                hederaToken, true, initSupply, decimals, senderId, nativeOperations, addressIdConverter);
        return bodyFor(tokenCreateWrapper);
    }

    /**
     * Decodes a call to {@link CreateTranslator#CREATE_FUNGIBLE_TOKEN_V2} into a synthetic {@link TransactionBody}.
     *
     * @param encoded the encoded call
     * @param senderId the id of the sender account
     * @param nativeOperations the Hedera native operation
     * @param addressIdConverter the address ID converter for this call
     * @return the synthetic transaction body
     */
    public TransactionBody decodeCreateFungibleTokenV2(
            @NonNull final byte[] encoded,
            @NonNull final AccountID senderId,
            @NonNull final HederaNativeOperations nativeOperations,
            @NonNull final AddressIdConverter addressIdConverter) {
        final var call = CreateTranslator.CREATE_FUNGIBLE_TOKEN_V2.decodeCall(encoded);
        final var hederaToken = (Tuple) call.get(HEDERA_TOKEN);
        final var initSupply = ((BigInteger) call.get(INIT_SUPPLY)).longValueExact();
        final var decimals = ((Long) call.get(DECIMALS)).intValue();
        final TokenCreateWrapper tokenCreateWrapper = getTokenCreateWrapper(
                hederaToken, true, initSupply, decimals, senderId, nativeOperations, addressIdConverter);
        return bodyFor(tokenCreateWrapper);
    }

    /**
     * Decodes a call to {@link CreateTranslator#CREATE_FUNGIBLE_TOKEN_V3} into a synthetic {@link TransactionBody}.
     *
     * @param encoded the encoded call
     * @param senderId the id of the sender account
     * @param nativeOperations the Hedera native operation
     * @param addressIdConverter the address ID converter for this call
     * @return the synthetic transaction body
     */
    public TransactionBody decodeCreateFungibleTokenV3(
            @NonNull final byte[] encoded,
            @NonNull final AccountID senderId,
            @NonNull final HederaNativeOperations nativeOperations,
            @NonNull final AddressIdConverter addressIdConverter) {
        final var call = CreateTranslator.CREATE_FUNGIBLE_TOKEN_V3.decodeCall(encoded);
        final TokenCreateWrapper tokenCreateWrapper = getTokenCreateWrapper(
                call.get(HEDERA_TOKEN),
                true,
                call.get(INIT_SUPPLY),
                call.get(DECIMALS),
                senderId,
                nativeOperations,
                addressIdConverter);
        return bodyFor(tokenCreateWrapper);
    }

    /**
     * Decodes a call to {@link CreateTranslator#CREATE_FUNGIBLE_WITH_CUSTOM_FEES_V1} into a synthetic {@link TransactionBody}.
     *
     * @param encoded the encoded call
     * @param senderId the id of the sender account
     * @param nativeOperations the Hedera native operation
     * @param addressIdConverter the address ID converter for this call
     * @return the synthetic transaction body
     */
    public TransactionBody decodeCreateFungibleTokenWithCustomFeesV1(
            @NonNull final byte[] encoded,
            @NonNull final AccountID senderId,
            @NonNull final HederaNativeOperations nativeOperations,
            @NonNull final AddressIdConverter addressIdConverter) {
        final var call = CreateTranslator.CREATE_FUNGIBLE_WITH_CUSTOM_FEES_V1.decodeCall(encoded);
        final var hederaToken = (Tuple) call.get(HEDERA_TOKEN);
        final var initSupply = ((BigInteger) call.get(INIT_SUPPLY)).longValueExact();
        final var decimals = ((BigInteger) call.get(DECIMALS)).intValue();
        final var fixedFee = (Tuple[]) call.get(FIXED_FEE);
        final var fractionalFees = (Tuple[]) call.get(FRACTIONAL_FEE);
        final TokenCreateWrapper tokenCreateWrapper = getTokenCreateWrapperFungibleWithCustomFees(
                hederaToken,
                initSupply,
                decimals,
                fixedFee,
                fractionalFees,
                senderId,
                nativeOperations,
                addressIdConverter);
        return bodyFor(tokenCreateWrapper);
    }

    /**
     * Decodes a call to {@link CreateTranslator#CREATE_FUNGIBLE_WITH_CUSTOM_FEES_V2} into a synthetic {@link TransactionBody}.
     *
     * @param encoded the encoded call
     * @param senderId the id of the sender account
     * @param nativeOperations the Hedera native operation
     * @param addressIdConverter the address ID converter for this call
     * @return the synthetic transaction body
     */
    public TransactionBody decodeCreateFungibleTokenWithCustomFeesV2(
            @NonNull final byte[] encoded,
            @NonNull final AccountID senderId,
            @NonNull final HederaNativeOperations nativeOperations,
            @NonNull final AddressIdConverter addressIdConverter) {
        final var call = CreateTranslator.CREATE_FUNGIBLE_WITH_CUSTOM_FEES_V2.decodeCall(encoded);
        final var hederaToken = (Tuple) call.get(HEDERA_TOKEN);
        final var initSupply = ((BigInteger) call.get(INIT_SUPPLY)).longValueExact();
        final var decimals = ((Long) call.get(DECIMALS)).intValue();
        final var fixedFee = (Tuple[]) call.get(FIXED_FEE);
        final var fractionalFees = (Tuple[]) call.get(FRACTIONAL_FEE);
        final TokenCreateWrapper tokenCreateWrapper = getTokenCreateWrapperFungibleWithCustomFees(
                hederaToken,
                initSupply,
                decimals,
                fixedFee,
                fractionalFees,
                senderId,
                nativeOperations,
                addressIdConverter);
        return bodyFor(tokenCreateWrapper);
    }

    /**
     * Decodes a call to {@link CreateTranslator#CREATE_FUNGIBLE_WITH_CUSTOM_FEES_V3} into a synthetic {@link TransactionBody}.
     *
     * @param encoded the encoded call
     * @param senderId the id of the sender account
     * @param nativeOperations the Hedera native operation
     * @param addressIdConverter the address ID converter for this call
     * @return the synthetic transaction body
     */
    public TransactionBody decodeCreateFungibleTokenWithCustomFeesV3(
            @NonNull final byte[] encoded,
            @NonNull final AccountID senderId,
            @NonNull final HederaNativeOperations nativeOperations,
            @NonNull final AddressIdConverter addressIdConverter) {
        final var call = CreateTranslator.CREATE_FUNGIBLE_WITH_CUSTOM_FEES_V3.decodeCall(encoded);
        final TokenCreateWrapper tokenCreateWrapper = getTokenCreateWrapperFungibleWithCustomFees(
                call.get(HEDERA_TOKEN),
                call.get(INIT_SUPPLY),
                call.get(DECIMALS),
                call.get(FIXED_FEE),
                call.get(FRACTIONAL_FEE),
                senderId,
                nativeOperations,
                addressIdConverter);
        return bodyFor(tokenCreateWrapper);
    }

    /**
     * Decodes a call to {@link CreateTranslator#CREATE_NON_FUNGIBLE_TOKEN_V1} into a synthetic {@link TransactionBody}.
     *
     * @param encoded the encoded call
     * @param senderId the id of the sender account
     * @param nativeOperations the Hedera native operation
     * @param addressIdConverter the address ID converter for this call
     * @return the synthetic transaction body
     */
    public TransactionBody decodeCreateNonFungibleV1(
            @NonNull final byte[] encoded,
            @NonNull final AccountID senderId,
            @NonNull final HederaNativeOperations nativeOperations,
            @NonNull final AddressIdConverter addressIdConverter) {
        final var call = CreateTranslator.CREATE_NON_FUNGIBLE_TOKEN_V1.decodeCall(encoded);
        final TokenCreateWrapper tokenCreateWrapper = getTokenCreateWrapperNonFungible(
                call.get(HEDERA_TOKEN), senderId, nativeOperations, addressIdConverter);
        return bodyFor(tokenCreateWrapper);
    }

    /**
     * Decodes a call to {@link CreateTranslator#CREATE_NON_FUNGIBLE_TOKEN_V2} into a synthetic {@link TransactionBody}.
     *
     * @param encoded the encoded call
     * @param senderId the id of the sender account
     * @param nativeOperations the Hedera native operation
     * @param addressIdConverter the address ID converter for this call
     * @return the synthetic transaction body
     */
    public TransactionBody decodeCreateNonFungibleV2(
            @NonNull final byte[] encoded,
            @NonNull final AccountID senderId,
            @NonNull final HederaNativeOperations nativeOperations,
            @NonNull final AddressIdConverter addressIdConverter) {
        final var call = CreateTranslator.CREATE_NON_FUNGIBLE_TOKEN_V2.decodeCall(encoded);
        final TokenCreateWrapper tokenCreateWrapper = getTokenCreateWrapperNonFungible(
                call.get(HEDERA_TOKEN), senderId, nativeOperations, addressIdConverter);
        return bodyFor(tokenCreateWrapper);
    }

    /**
     * Decodes a call to {@link CreateTranslator#CREATE_NON_FUNGIBLE_TOKEN_V3} into a synthetic {@link TransactionBody}.
     *
     * @param encoded the encoded call
     * @param senderId the id of the sender account
     * @param nativeOperations the Hedera native operation
     * @param addressIdConverter the address ID converter for this call
     * @return the synthetic transaction body
     */
    public TransactionBody decodeCreateNonFungibleV3(
            @NonNull final byte[] encoded,
            @NonNull final AccountID senderId,
            @NonNull final HederaNativeOperations nativeOperations,
            @NonNull final AddressIdConverter addressIdConverter) {
        final var call = CreateTranslator.CREATE_NON_FUNGIBLE_TOKEN_V3.decodeCall(encoded);
        final TokenCreateWrapper tokenCreateWrapper = getTokenCreateWrapperNonFungible(
                call.get(HEDERA_TOKEN), senderId, nativeOperations, addressIdConverter);
        return bodyFor(tokenCreateWrapper);
    }

    /**
     * Decodes a call to {@link CreateTranslator#CREATE_NON_FUNGIBLE_TOKEN_WITH_CUSTOM_FEES_V1} into a synthetic {@link TransactionBody}.
     *
     * @param encoded the encoded call
     * @param senderId the id of the sender account
     * @param nativeOperations the Hedera native operation
     * @param addressIdConverter the address ID converter for this call
     * @return the synthetic transaction body
     */
    public TransactionBody decodeCreateNonFungibleWithCustomFeesV1(
            @NonNull final byte[] encoded,
            @NonNull final AccountID senderId,
            @NonNull final HederaNativeOperations nativeOperations,
            @NonNull final AddressIdConverter addressIdConverter) {
        final var call = CreateTranslator.CREATE_NON_FUNGIBLE_TOKEN_WITH_CUSTOM_FEES_V1.decodeCall(encoded);
        final TokenCreateWrapper tokenCreateWrapper = getTokenCreateWrapperNonFungibleWithCustomFees(
                call.get(HEDERA_TOKEN),
                call.get(NFT_FIXED_FEE),
                call.get(NFT_ROYALTY_FEE),
                senderId,
                nativeOperations,
                addressIdConverter);
        return bodyFor(tokenCreateWrapper);
    }

    /**
     * Decodes a call to {@link CreateTranslator#CREATE_NON_FUNGIBLE_TOKEN_WITH_CUSTOM_FEES_V2} into a synthetic {@link TransactionBody}.
     *
     * @param encoded the encoded call
     * @param senderId the id of the sender account
     * @param nativeOperations the Hedera native operation
     * @param addressIdConverter the address ID converter for this call
     * @return the synthetic transaction body
     */
    public TransactionBody decodeCreateNonFungibleWithCustomFeesV2(
            @NonNull final byte[] encoded,
            @NonNull final AccountID senderId,
            @NonNull final HederaNativeOperations nativeOperations,
            @NonNull final AddressIdConverter addressIdConverter) {
        final var call = CreateTranslator.CREATE_NON_FUNGIBLE_TOKEN_WITH_CUSTOM_FEES_V2.decodeCall(encoded);
        final TokenCreateWrapper tokenCreateWrapper = getTokenCreateWrapperNonFungibleWithCustomFees(
                call.get(HEDERA_TOKEN),
                call.get(NFT_FIXED_FEE),
                call.get(NFT_ROYALTY_FEE),
                senderId,
                nativeOperations,
                addressIdConverter);
        return bodyFor(tokenCreateWrapper);
    }

    /**
     * Decodes a call to {@link CreateTranslator#CREATE_NON_FUNGIBLE_TOKEN_WITH_CUSTOM_FEES_V3} into a synthetic {@link TransactionBody}.
     *
     * @param encoded the encoded call
     * @param senderId the id of the sender account
     * @param nativeOperations the Hedera native operation
     * @param addressIdConverter the address ID converter for this call
     * @return the synthetic transaction body
     */
    public TransactionBody decodeCreateNonFungibleWithCustomFeesV3(
            @NonNull final byte[] encoded,
            @NonNull final AccountID senderId,
            @NonNull final HederaNativeOperations nativeOperations,
            @NonNull final AddressIdConverter addressIdConverter) {
        final var call = CreateTranslator.CREATE_NON_FUNGIBLE_TOKEN_WITH_CUSTOM_FEES_V3.decodeCall(encoded);
        final TokenCreateWrapper tokenCreateWrapper = getTokenCreateWrapperNonFungibleWithCustomFees(
                call.get(HEDERA_TOKEN),
                call.get(NFT_FIXED_FEE),
                call.get(NFT_ROYALTY_FEE),
                senderId,
                nativeOperations,
                addressIdConverter);
        return bodyFor(tokenCreateWrapper);
    }

    private TokenCreateWrapper getTokenCreateWrapperFungibleWithCustomFees(
            @NonNull final Tuple tokenCreateStruct,
            final long initSupply,
            final int decimals,
            @NonNull final Tuple[] fixedFeesTuple,
            @NonNull final Tuple[] fractionalFeesTuple,
            @NonNull final AccountID senderId,
            @NonNull final HederaNativeOperations nativeOperations,
            @NonNull final AddressIdConverter addressIdConverter) {
        final var tokenCreateWrapper = getTokenCreateWrapper(
                tokenCreateStruct, true, initSupply, decimals, senderId, nativeOperations, addressIdConverter);
        final var fixedFees = decodeFixedFees(fixedFeesTuple, addressIdConverter, nativeOperations.entityIdFactory());
        final var fractionalFess = decodeFractionalFees(fractionalFeesTuple, addressIdConverter);
        tokenCreateWrapper.setFixedFees(fixedFees);
        tokenCreateWrapper.setFractionalFees(fractionalFess);
        return tokenCreateWrapper;
    }

    private TokenCreateWrapper getTokenCreateWrapperNonFungible(
            @NonNull final Tuple tokenCreateStruct,
            @NonNull final AccountID senderId,
            @NonNull final HederaNativeOperations nativeOperations,
            @NonNull final AddressIdConverter addressIdConverter) {
        final long initSupply = 0L;
        final int decimals = 0;
        return getTokenCreateWrapper(
                tokenCreateStruct, false, initSupply, decimals, senderId, nativeOperations, addressIdConverter);
    }

    private TokenCreateWrapper getTokenCreateWrapperNonFungibleWithCustomFees(
            @NonNull final Tuple tokenCreateStruct,
            @NonNull final Tuple[] fixedFeesTuple,
            @NonNull final Tuple[] royaltyFeesTuple,
            @NonNull final AccountID senderId,
            @NonNull final HederaNativeOperations nativeOperations,
            @NonNull final AddressIdConverter addressIdConverter) {
        final var fixedFees = decodeFixedFees(fixedFeesTuple, addressIdConverter, nativeOperations.entityIdFactory());
        final var royaltyFees =
                decodeRoyaltyFees(royaltyFeesTuple, addressIdConverter, nativeOperations.entityIdFactory());
        final long initSupply = 0L;
        final int decimals = 0;
        final var tokenCreateWrapper = getTokenCreateWrapper(
                tokenCreateStruct, false, initSupply, decimals, senderId, nativeOperations, addressIdConverter);
        tokenCreateWrapper.setFixedFees(fixedFees);
        tokenCreateWrapper.setRoyaltyFees(royaltyFees);
        return tokenCreateWrapper;
    }

    private @Nullable TransactionBody bodyFor(@NonNull final TokenCreateWrapper tokenCreateWrapper) {
        try {
            return bodyOf(createToken(tokenCreateWrapper));
        } catch (IllegalArgumentException ignore) {
            return null;
        }
    }
}
