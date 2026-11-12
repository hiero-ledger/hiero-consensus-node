// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.utils;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.node.app.service.contract.impl.exec.utils.TokenKeyWrapper.KEY_TYPES_167;
import static com.hedera.node.app.service.contract.impl.exec.utils.TokenKeyWrapper.KEY_TYPES_16C;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.asNumericContractId;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.token.TokenCreateTransactionBody;
import com.hedera.hapi.node.token.TokenUpdateTransactionBody;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.AddressIdConverter;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

public final class TokenKeyUtils {

    private static final int KEY_TYPE = 0;
    private static final int KEY_VALUE = 1;

    private static final int INHERIT_ACCOUNT_KEY = 0;
    private static final int CONTRACT_ID = 1;
    private static final int ED25519 = 2;
    private static final int ECDSA_SECP_256K1 = 3;
    private static final int DELEGATABLE_CONTRACT_ID = 4;

    private TokenKeyUtils() {}

    // TODO Glib; move AddressIdConverter to utils
    /**
     * Decodes the {@code TokenKey[]} of a token create or update call, keeping the entries in call order.
     * A contract id or delegatable contract id of zero is treated as unset.
     *
     * @param tokenKeysTuples the decoded {@code TokenKey} tuples
     * @param addressIdConverter the converter for the contract addresses in the key values
     * @param nativeOperation the native operations, used for the entity id factory
     * @return the key type and key value of each entry
     */
    public static List<TokenKeyWrapper> decodeTokenKeys(
            @NonNull final Tuple[] tokenKeysTuples,
            @NonNull final AddressIdConverter addressIdConverter,
            @NonNull final HederaNativeOperations nativeOperation) {
        final List<TokenKeyWrapper> tokenKeys = new ArrayList<>(tokenKeysTuples.length);
        for (final var tokenKeyTuple : tokenKeysTuples) {
            final var keyType = ((BigInteger) tokenKeyTuple.get(KEY_TYPE)).intValue();
            final Tuple keyValueTuple = tokenKeyTuple.get(KEY_VALUE);
            final var inheritAccountKey = (Boolean) keyValueTuple.get(INHERIT_ACCOUNT_KEY);
            final var entityIdFactory = nativeOperation.entityIdFactory();
            final var contractId =
                    asNumericContractId(entityIdFactory, addressIdConverter.convert(keyValueTuple.get(CONTRACT_ID)));
            final byte[] ed25519 = keyValueTuple.get(ED25519);
            final byte[] ecdsaSecp256K1 = keyValueTuple.get(ECDSA_SECP_256K1);
            final var delegatableContractId = asNumericContractId(
                    entityIdFactory, addressIdConverter.convert(keyValueTuple.get(DELEGATABLE_CONTRACT_ID)));

            tokenKeys.add(new TokenKeyWrapper(
                    keyType,
                    new KeyValueWrapper(
                            inheritAccountKey,
                            contractId.contractNumOrThrow() != 0 ? contractId : null,
                            ed25519,
                            ecdsaSecp256K1,
                            delegatableContractId.contractNumOrThrow() != 0 ? delegatableContractId : null)));
        }

        return tokenKeys;
    }

    /**
     * Sets on the builder each key whose bit is set in the key type of an entry. Entries apply in order, so for
     * repeated bits the last entry wins, and bits that select no key are ignored.
     * <p>
     * An entry whose key type has no defined bit (including zero) would select no key, so it is reported to the
     * caller. An empty list is not an error, since nothing was asked for, and undefined bits next to a defined one
     * are ignored.
     * <p>
     * Duplicated for {@link TokenCreateTransactionBody.Builder} below; keep the two in sync.
     *
     * @param tokenKeys the decoded key entries
     * @param withMetadata whether the metadata key bit is defined, which it is only at the 0x16c address
     * @param builder the update body builder to set the keys on
     * @return {@link ResponseCodeEnum#INVALID_TRANSACTION_BODY} if some entry selects no key, otherwise null
     * @throws IllegalArgumentException if an entry has an empty key value
     */
    @Nullable
    public static ResponseCodeEnum addTokenKeys(
            @NonNull final List<TokenKeyWrapper> tokenKeys,
            final boolean withMetadata,
            @NonNull final TokenUpdateTransactionBody.Builder builder) {
        final var definedKeyTypes = withMetadata ? KEY_TYPES_16C : KEY_TYPES_167;
        ResponseCodeEnum status = null;
        for (final var tokenKeyWrapper : tokenKeys) {
            final var key = tokenKeyWrapper.key().asGrpc();
            if (Key.DEFAULT.equals(key)) {
                throw new IllegalArgumentException();
            }
            if (tokenKeyWrapper.selectsNoKey(definedKeyTypes)) {
                status = INVALID_TRANSACTION_BODY;
            } else {
                if (tokenKeyWrapper.isUsedForAdminKey()) {
                    builder.adminKey(key);
                }
                if (tokenKeyWrapper.isUsedForKycKey()) {
                    builder.kycKey(key);
                }
                if (tokenKeyWrapper.isUsedForFreezeKey()) {
                    builder.freezeKey(key);
                }
                if (tokenKeyWrapper.isUsedForWipeKey()) {
                    builder.wipeKey(key);
                }
                if (tokenKeyWrapper.isUsedForSupplyKey()) {
                    builder.supplyKey(key);
                }
                if (tokenKeyWrapper.isUsedForFeeScheduleKey()) {
                    builder.feeScheduleKey(key);
                }
                if (tokenKeyWrapper.isUsedForPauseKey()) {
                    builder.pauseKey(key);
                }
                if (withMetadata && tokenKeyWrapper.isUsedForMetadataKey()) {
                    builder.metadataKey(key);
                }
            }
        }
        return status;
    }

    /**
     * Sets on the builder each key whose bit is set in the key type of an entry. Entries apply in order, so for
     * repeated bits the last entry wins, and bits that select no key are ignored.
     * <p>
     * An entry whose key type has no defined bit (including zero) would select no key, so it is reported to the
     * caller. An empty list is not an error, since nothing was asked for, and undefined bits next to a defined one
     * are ignored.
     * <p>
     * Duplicated for {@link TokenUpdateTransactionBody.Builder} above; keep the two in sync.
     *
     * @param tokenKeys the decoded key entries
     * @param withMetadata whether the metadata key bit is defined, which it is only at the 0x16c address
     * @param builder the create body builder to set the keys on
     * @return {@link ResponseCodeEnum#INVALID_TRANSACTION_BODY} if some entry selects no key, otherwise null
     * @throws IllegalArgumentException if an entry has an empty key value
     */
    @Nullable
    public static ResponseCodeEnum addTokenKeys(
            @NonNull final List<TokenKeyWrapper> tokenKeys,
            final boolean withMetadata,
            @NonNull final TokenCreateTransactionBody.Builder builder) {
        final var definedKeyTypes = withMetadata ? KEY_TYPES_16C : KEY_TYPES_167;
        ResponseCodeEnum status = null;
        for (final var tokenKeyWrapper : tokenKeys) {
            final var key = tokenKeyWrapper.key().asGrpc();
            if (Key.DEFAULT.equals(key)) {
                throw new IllegalArgumentException();
            }
            if (tokenKeyWrapper.selectsNoKey(definedKeyTypes)) {
                status = INVALID_TRANSACTION_BODY;
            } else {
                if (tokenKeyWrapper.isUsedForAdminKey()) {
                    builder.adminKey(key);
                }
                if (tokenKeyWrapper.isUsedForKycKey()) {
                    builder.kycKey(key);
                }
                if (tokenKeyWrapper.isUsedForFreezeKey()) {
                    builder.freezeKey(key);
                }
                if (tokenKeyWrapper.isUsedForWipeKey()) {
                    builder.wipeKey(key);
                }
                if (tokenKeyWrapper.isUsedForSupplyKey()) {
                    builder.supplyKey(key);
                }
                if (tokenKeyWrapper.isUsedForFeeScheduleKey()) {
                    builder.feeScheduleKey(key);
                }
                if (tokenKeyWrapper.isUsedForPauseKey()) {
                    builder.pauseKey(key);
                }
                if (withMetadata && tokenKeyWrapper.isUsedForMetadataKey()) {
                    builder.metadataKey(key);
                }
            }
        }
        return status;
    }
}
