// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.create.address_0x16c;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.create.CreateSyntheticTxnFactory.createTokenWithMetadata;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.AddressIdConverter;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.create.CreateCommonDecoder;
import com.hedera.node.app.service.contract.impl.exec.utils.TokenCreateWrapper;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;

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
     * Decodes a call to {@link CreateTranslator#CREATE_FUNGIBLE_TOKEN_WITH_METADATA} into a synthetic {@link TransactionBody}.
     *
     * @param encoded the encoded call
     * @param senderId the sender account ID
     * @param nativeOperations the native operations
     * @param addressIdConverter the address ID converter
     * @return the synthetic transaction body
     */
    public TransactionBody decodeCreateFungibleTokenWithMetadata(
            @NonNull final byte[] encoded,
            @NonNull final AccountID senderId,
            @NonNull final HederaNativeOperations nativeOperations,
            @NonNull final AddressIdConverter addressIdConverter) {
        final var call = CreateTranslator.CREATE_FUNGIBLE_TOKEN_WITH_METADATA.decodeCall(encoded);
        final TokenCreateWrapper tokenCreateWrapper = getTokenCreateWrapperWithMetadata(
                call.get(HEDERA_TOKEN),
                true,
                call.get(INIT_SUPPLY),
                call.get(DECIMALS),
                senderId,
                nativeOperations,
                addressIdConverter);
        return bodyForWithMeta(tokenCreateWrapper);
    }

    /**
     * Decodes a call to {@link CreateTranslator#CREATE_FUNGIBLE_TOKEN_WITH_METADATA_AND_CUSTOM_FEES} into a synthetic {@link TransactionBody}.
     *
     * @param encoded the encoded call
     * @param senderId the sender account ID
     * @param nativeOperations the native operations
     * @param addressIdConverter the address ID converter
     * @return the synthetic transaction body
     */
    public TransactionBody decodeCreateFungibleTokenWithMetadataAndCustomFees(
            @NonNull final byte[] encoded,
            @NonNull final AccountID senderId,
            @NonNull final HederaNativeOperations nativeOperations,
            @NonNull final AddressIdConverter addressIdConverter) {
        final var call = CreateTranslator.CREATE_FUNGIBLE_TOKEN_WITH_METADATA_AND_CUSTOM_FEES.decodeCall(encoded);
        final TokenCreateWrapper tokenCreateWrapper = getTokenCreateWrapperWithMetadataAndCustomFees(
                call.get(HEDERA_TOKEN),
                call.get(INIT_SUPPLY),
                call.get(DECIMALS),
                call.get(FIXED_FEE),
                call.get(FRACTIONAL_FEE),
                senderId,
                nativeOperations,
                addressIdConverter);
        return bodyForWithMeta(tokenCreateWrapper);
    }

    /**
     * Decodes a call to {@link CreateTranslator#CREATE_NON_FUNGIBLE_TOKEN_WITH_METADATA} into a synthetic {@link TransactionBody}.
     *
     * @param encoded the encoded call
     * @param senderId the sender account ID
     * @param nativeOperations the native operations
     * @param addressIdConverter the address ID converter
     * @return the synthetic transaction body
     */
    public TransactionBody decodeCreateNonFungibleWithMetadata(
            @NonNull final byte[] encoded,
            @NonNull final AccountID senderId,
            @NonNull final HederaNativeOperations nativeOperations,
            @NonNull final AddressIdConverter addressIdConverter) {
        final var call = CreateTranslator.CREATE_NON_FUNGIBLE_TOKEN_WITH_METADATA.decodeCall(encoded);
        final TokenCreateWrapper tokenCreateWrapper = getTokenCreateWrapperNonFungibleWithMetadata(
                call.get(HEDERA_TOKEN), senderId, nativeOperations, addressIdConverter);
        return bodyForWithMeta(tokenCreateWrapper);
    }

    /**
     * Decodes a call to {@link CreateTranslator#CREATE_NON_FUNGIBLE_TOKEN_WITH_METADATA_AND_CUSTOM_FEES} into a synthetic {@link TransactionBody}.
     *
     * @param encoded the encoded call
     * @param senderId the sender account ID
     * @param nativeOperations the native operations
     * @param addressIdConverter the address ID converter
     * @return the synthetic transaction body
     */
    public TransactionBody decodeCreateNonFungibleWithMetadataAndCustomFees(
            @NonNull final byte[] encoded,
            @NonNull final AccountID senderId,
            @NonNull final HederaNativeOperations nativeOperations,
            @NonNull final AddressIdConverter addressIdConverter) {
        final var call = CreateTranslator.CREATE_NON_FUNGIBLE_TOKEN_WITH_METADATA_AND_CUSTOM_FEES.decodeCall(encoded);
        final TokenCreateWrapper tokenCreateWrapper = getTokenCreateWrapperNonFungibleWithMetadataAndCustomFees(
                call.get(HEDERA_TOKEN),
                call.get(NFT_FIXED_FEE),
                call.get(NFT_ROYALTY_FEE),
                senderId,
                nativeOperations,
                addressIdConverter);
        return bodyForWithMeta(tokenCreateWrapper);
    }

    /**
     * @param tokenCreateStruct the token struct to use
     * @param isFungible whether the token is fungible
     * @param initSupply the initial supply of the token
     * @param decimals decimals of the token
     * @param senderId the sender account id
     * @param nativeOperations the Hedera native operation
     * @param addressIdConverter the address ID converter for this call
     * @return a token create wrapper object
     */
    public TokenCreateWrapper getTokenCreateWrapperWithMetadata(
            @NonNull final Tuple tokenCreateStruct,
            final boolean isFungible,
            final long initSupply,
            final int decimals,
            @NonNull final AccountID senderId,
            @NonNull final HederaNativeOperations nativeOperations,
            @NonNull final AddressIdConverter addressIdConverter) {
        final var tokenCreateWrapper = getTokenCreateWrapper(
                tokenCreateStruct, isFungible, initSupply, decimals, senderId, nativeOperations, addressIdConverter);

        tokenCreateWrapper.setMetadata(Bytes.wrap((byte[]) tokenCreateStruct.get(9)));
        return tokenCreateWrapper;
    }

    private TokenCreateWrapper getTokenCreateWrapperWithMetadataAndCustomFees(
            @NonNull final Tuple tokenCreateStruct,
            final long initSupply,
            final int decimals,
            @NonNull final Tuple[] fixedFeesTuple,
            @NonNull final Tuple[] fractionalFeesTuple,
            @NonNull final AccountID senderId,
            @NonNull final HederaNativeOperations nativeOperations,
            @NonNull final AddressIdConverter addressIdConverter) {
        final var tokenCreateWrapper = getTokenCreateWrapperWithMetadata(
                tokenCreateStruct, true, initSupply, decimals, senderId, nativeOperations, addressIdConverter);
        final var fixedFees = decodeFixedFees(fixedFeesTuple, addressIdConverter, nativeOperations.entityIdFactory());
        final var fractionalFess = decodeFractionalFees(fractionalFeesTuple, addressIdConverter);
        tokenCreateWrapper.setFixedFees(fixedFees);
        tokenCreateWrapper.setFractionalFees(fractionalFess);
        return tokenCreateWrapper;
    }

    private TokenCreateWrapper getTokenCreateWrapperNonFungibleWithMetadata(
            @NonNull final Tuple tokenCreateStruct,
            @NonNull final AccountID senderId,
            @NonNull final HederaNativeOperations nativeOperations,
            @NonNull final AddressIdConverter addressIdConverter) {
        final long initSupply = 0L;
        final int decimals = 0;
        return getTokenCreateWrapperWithMetadata(
                tokenCreateStruct, false, initSupply, decimals, senderId, nativeOperations, addressIdConverter);
    }

    private TokenCreateWrapper getTokenCreateWrapperNonFungibleWithMetadataAndCustomFees(
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
        final var tokenCreateWrapper = getTokenCreateWrapperWithMetadata(
                tokenCreateStruct, false, initSupply, decimals, senderId, nativeOperations, addressIdConverter);
        tokenCreateWrapper.setFixedFees(fixedFees);
        tokenCreateWrapper.setRoyaltyFees(royaltyFees);
        return tokenCreateWrapper;
    }

    private @Nullable TransactionBody bodyForWithMeta(@NonNull final TokenCreateWrapper tokenCreateWrapper) {
        try {
            return bodyOf(createTokenWithMetadata(tokenCreateWrapper));
        } catch (IllegalArgumentException ignore) {
            return null;
        }
    }
}
