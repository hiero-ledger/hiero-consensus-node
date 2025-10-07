// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_SIGNATURE;
import static java.util.Objects.requireNonNull;

import com.esaulpaugh.headlong.abi.Tuple;
import com.esaulpaugh.headlong.abi.TupleType;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.ByteBuffer;
import org.apache.tuweni.bytes.Bytes;

/**
 * Literal representations of output types used by HTS system contract functions.
 */
public class ReturnTypes {

    // Token info fields
    protected static final String TOKEN_FIELDS =
            // name, symbol, treasury, memo, tokenSupplyType, maxSupply, freezeDefault
            "string,string,address,string,bool,int64,bool,";
    protected static final String TOKEN_KEYS = "("
            // keyType
            + "uint256,"
            // KeyValue
            // inheritedAccountKey, contractId, ed25519, ECDSA_secp256k1, delegatableContractId
            + "(bool,address,bytes,bytes,address)"
            + ")[],";
    protected static final String EXPIRY_FIELDS =
            // second, autoRenewAccount, autoRenewPeriod
            "(uint32,address,uint32)";
    // TODO: consider this expiry type for TokenV3. Might need to add another function to handle this.
    protected static final String EXPIRY_FIELDS_V2 =
            // second, autoRenewAccount, autoRenewPeriod
            "(int64,address,int64)";
    protected static final String CUSTOM_FEES =
            // FixedFee array
            // amount, tokenId, useHbarsForPayment, useCurrentTokenForPayment, feeCollector
            "(uint32,address,bool,bool,address)[],"
                    // FractionalFee array
                    // numerator, denominator, minimumAmount, maximumAmount, netOfTransfers, feeCollector
                    + "(uint32,uint32,uint32,uint32,bool,address)[],"
                    // RoyaltyFee array
                    // numerator, denominator, amount, tokenId, useHbarsForPayment, feeCollector
                    + "(uint32,uint32,uint32,address,bool,address)[]";
    protected static final String STATUS_FIELDS =
            // totalSupply, deleted, defaultKycStatus, pauseStatus
            ",int64,bool,bool,bool,";

    // Response code types
    public static final String INT = "(int)";
    public static final String INT_64 = "(int64)";
    public static final String INT64_INT64 = "(int64,int64)";
    public static final String BYTE = "(uint8)";
    public static final String BOOL = "(bool)";
    public static final String STRING = "(string)";
    public static final String ADDRESS = "(address)";

    public static final String RESPONSE_CODE_BOOL = "(int32,bool)";
    public static final String RESPONSE_CODE64_BOOL = "(int64,bool)";
    public static final String RESPONSE_CODE_INT32 = "(int32,int32)";
    public static final String RESPONSE_CODE_UINT256 = "(int64,uint256)";
    public static final String RESPONSE_CODE_INT256 = "(int64,int256)";
    public static final String RESPONSE_CODE_ADDRESS = "(int64,address)";

    public static final String UINT256 = "(uint256)";
    public static final String RESPONSE_CODE_EXPIRY = "(int32,"
            // Expiry
            + "(int64,address,int64)" // second, autoRenewAccount, autoRenewPeriod
            + ")";
    public static final String RESPONSE_CODE_TOKEN_KEY = "(int32,"
            // KeyValue
            + "(bool,address,bytes,bytes,address)" // inheritedAccountKey, contractId, ed25519, ECDSA_secp256k1,
            // delegatableContractId
            + ")";

    // spotless:off
    public static final String RESPONSE_CODE_TOKEN_INFO = "(int32,"
            // TokenInfo
            + "("
                // HederaToken
                + "("
                    + TOKEN_FIELDS
                    // TokenKey array
                    + TOKEN_KEYS
                    // Expiry
                    + EXPIRY_FIELDS + ")"
                    + STATUS_FIELDS
                    + CUSTOM_FEES
                    + ",string" // ledgerId
                + ")"
            + ")";

    public static final String RESPONSE_CODE_TOKEN_INFO_V2 = "(int32,"
            // TokenInfoV2
            + "("
                // HederaTokenV2
                + "("
                    + TOKEN_FIELDS
                    + TOKEN_KEYS
                    + EXPIRY_FIELDS_V2
                    + ",bytes" // metadata
                    + ")"
                    + STATUS_FIELDS // totalSupply, deleted, defaultKycStatus, pauseStatus
                    + CUSTOM_FEES
                    + ",string" // ledgerId
                + ")"
            + ")";

    public static final String RESPONSE_CODE_FUNGIBLE_TOKEN_INFO = "(int32,"
            // FungibleTokenInfo
            + "("
                // TokenInfo
                + "("
                    // HederaToken
                    + "("
                        + TOKEN_FIELDS
                        + TOKEN_KEYS
                        + EXPIRY_FIELDS + ")"
                        + STATUS_FIELDS
                        + CUSTOM_FEES
                        + ",string" // ledgerId
                    + "),"
                    + "int32" // decimals
                + ")"
            + ")";

    public static final String RESPONSE_CODE_FUNGIBLE_TOKEN_INFO_V2 = "(int32,"
            // FungibleTokenInfoV2
            + "("
                // TokenInfoV2
                + "("
                    // HederaTokenV2
                    + "("
                        + TOKEN_FIELDS
                        + TOKEN_KEYS
                        + EXPIRY_FIELDS_V2
                        + ",bytes" // metadata
                         + ")"
                        + STATUS_FIELDS
                        + CUSTOM_FEES
                        + ",string" // ledgerId
                    + "),"
                    + "int32" // decimals
                + ")"
            + ")";

    public static final String RESPONSE_CODE_NON_FUNGIBLE_TOKEN_INFO = "(int32,"
            // NonFungibleTokenInfo
            + "("
                // TokenInfo
                + "("
                    // HederaToken
                    + "("
                        + TOKEN_FIELDS
                        + TOKEN_KEYS
                        + EXPIRY_FIELDS + ")"
                        + STATUS_FIELDS
                        + CUSTOM_FEES
                        + ",string" // ledgerId
                    + "),"
                    + "int64,address,int64,bytes,address" // serialNumber, ownerId, creationTime, metadata, spenderId
                + ")"
            + ")";

    public static final String RESPONSE_CODE_NON_FUNGIBLE_TOKEN_INFO_V2 = "(int32,"
            // NonFungibleTokenInfoV2
            + "("
                // TokenInfoV2
                + "("
                    // HederaTokenV2
                    + "("
                        + TOKEN_FIELDS
                        + TOKEN_KEYS
                        + EXPIRY_FIELDS_V2
                        + ",bytes" // metadata
                        + ")"
                        + STATUS_FIELDS // totalSupply, deleted, defaultKycStatus, pauseStatus
                        + CUSTOM_FEES
                        + ",string" // ledgerId
                    + "),"
                    + "int64,address,int64,bytes,address" // serialNumber, ownerId, creationTime, metadata, spenderId
                + ")"
            + ")";

    public static final String RESPONSE_CODE_CUSTOM_FEES = "(int32,"
            // FixedFee array
            + "(uint32,address,bool,bool,address)[]," // amount, tokenId, useHbarsForPayment, useCurrentTokenForPayment, feeCollector
            // FractionalFee array
            + "(uint32,uint32,uint32,uint32,bool,address)[]," // numerator, denominator, minimumAmount, maximumAmount, netOfTransfers, feeCollector
            // RoyaltyFee array
            + "(uint32,uint32,uint32,address,bool,address)[]" // numerator, denominator, amount, tokenId, useHbarsForPayment, feeCollector
        + ")";
    // spotless:on

    public static final TupleType<Tuple> RC_AND_ADDRESS_ENCODER = TupleType.parse(RESPONSE_CODE_ADDRESS);
    private static final TupleType<Tuple> RC_ENCODER = TupleType.parse(INT_64);

    private ReturnTypes() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static Bytes tuweniEncodedRc(@NonNull final ResponseCodeEnum status) {
        return Bytes.wrap(encodedRc(status).array());
    }

    /**
     * Encodes the given {@code status} as a return value for a classic transfer call.
     *
     * @param status the status to encode
     * @return the encoded status
     */
    public static ByteBuffer encodedRc(@NonNull final ResponseCodeEnum status) {
        return RC_ENCODER.encode(Tuple.singleton((long) status.protoOrdinal()));
    }

    public static ResponseCodeEnum standardized(@NonNull final ResponseCodeEnum status) {
        return requireNonNull(status) == INVALID_SIGNATURE ? INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE : status;
    }
}
