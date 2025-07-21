// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.contracts;

import com.esaulpaugh.headlong.abi.TupleType;

public final class ParsingConstants {
    private ParsingConstants() {
        throw new UnsupportedOperationException("Utility class");
    }

    // data types
    public static final String INT_BOOL_PAIR_RETURN_TYPE = "(int32,bool)";
    public static final String ADDRESS = "(address)";
    public static final String BOOL = "(bool)";
    public static final String INT_BOOL_PAIR = "(int,bool)";
    public static final String BYTES32 = "(bytes32)";
    public static final String ADDRESS_PAIR_RAW_TYPE = "(bytes32,bytes32)";
    public static final String ADDRESS_UINT256_RAW_TYPE = "(bytes32,uint256)";
    public static final String ADDRESS_ADDRESS_UINT256_RAW_TYPE = "(bytes32,bytes32,uint256)";
    public static final String INT = "(int)";
    public static final String INT32 = "(int32)";
    public static final String STRING = "(string)";
    public static final String UINT8 = "(uint8)";
    public static final String UINT256 = "(uint256)";

    // struct types
    // Defined by struct Expiry { unint32 second; address autoRenewAccount; uint32 autoRenewPeriod; }
    public static final String EXPIRY = "(uint32,address,uint32)";
    public static final String EXPIRY_V2 = "(int64,address,int64)";
    public static final String FIXED_FEE = "(uint32,address,bool,bool,address)";
    public static final String FIXED_FEE_V2 = "(int64,address,bool,bool,address)";
    public static final String FRACTIONAL_FEE = "(uint32,uint32,uint32,uint32,bool,address)";
    public static final String FRACTIONAL_FEE_V2 = "(int64,int64,int64,int64,bool,address)";
    // Defined by struct KeyValue { bool inheritAccountKey; address contractId; bytes ed25519; bytes ECDSA_secp256k1;
    // address delegatableContractId; }
    public static final String KEY_VALUE = "(bool,address,bytes,bytes,address)";
    public static final String ROYALTY_FEE = "(uint32,uint32,uint32,address,bool,address)";
    public static final String ROYALTY_FEE_V2 = "(int64,int64,int64,address,bool,address)";
    public static final String TOKEN_KEY = "(uint256," + KEY_VALUE + ")";

    public static final String HEDERA_TOKEN_V1 =
            "(" + "string,string,address,string,bool,uint32,bool," + TOKEN_KEY + "[]," + EXPIRY + ")";
    public static final String HEDERA_TOKEN_V2 =
            "(" + "string,string,address,string,bool,int64,bool," + TOKEN_KEY + "[]," + EXPIRY + ")";
    public static final String HEDERA_TOKEN_V3 =
            "(" + "string,string,address,string,bool,int64,bool," + TOKEN_KEY + "[]," + EXPIRY_V2 + ")";
    public static final String HEDERA_TOKEN_WITH_METADATA =
            "(" + "string,string,address,string,bool,int64,bool," + TOKEN_KEY + "[]," + EXPIRY_V2 + ",bytes)";

    public static final String TOKEN_INFO = "("
            + HEDERA_TOKEN_V2
            + ",int64,bool,bool,bool,"
            + FIXED_FEE
            + "[],"
            + FRACTIONAL_FEE
            + "[],"
            + ROYALTY_FEE
            + "[],string"
            + ")";
    public static final String RESPONSE_STATUS_AT_BEGINNING = "(int32,";

    public static final String FUNGIBLE_TOKEN_INFO = "(" + TOKEN_INFO + ",int32" + ")";
    public static final String NON_FUNGIBLE_TOKEN_INFO = "(" + TOKEN_INFO + ",int64,address,int64,bytes,address" + ")";

    // tuple types
    public static final TupleType addressTuple = TupleType.parse(ADDRESS);
    public static final TupleType booleanTuple = TupleType.parse(BOOL);
    public static final TupleType stringTuple = TupleType.parse(STRING);
    public static final TupleType bigIntegerTuple = TupleType.parse(UINT256);
    public static final TupleType intTuple = TupleType.parse(INT32);
    public static final TupleType decimalsType = TupleType.parse(UINT8);
    public static final TupleType intBoolTuple = TupleType.parse(INT_BOOL_PAIR_RETURN_TYPE);

    public static final TupleType intAddressTuple = TupleType.parse("(int32,address)");
    public static final TupleType intPairTuple = TupleType.parse("(int32,int32)");
    public static final TupleType burnReturnType = TupleType.parse("(int32,uint64)");
    public static final TupleType mintReturnType = TupleType.parse("(int32,uint64,int64[])");
    public static final TupleType getFungibleTokenInfoType =
            TupleType.parse(RESPONSE_STATUS_AT_BEGINNING + FUNGIBLE_TOKEN_INFO + ")");
    public static final TupleType getTokenInfoType = TupleType.parse(RESPONSE_STATUS_AT_BEGINNING + TOKEN_INFO + ")");
    public static final TupleType getNonFungibleTokenInfoType =
            TupleType.parse(RESPONSE_STATUS_AT_BEGINNING + NON_FUNGIBLE_TOKEN_INFO + ")");
    public static final TupleType getTokenCustomFeesType = TupleType.parse(
            RESPONSE_STATUS_AT_BEGINNING + FIXED_FEE + "[]," + FRACTIONAL_FEE + "[]," + ROYALTY_FEE + "[])");
    public static final TupleType hapiAllowanceOfType = TupleType.parse("(int32,uint256)");
    public static final TupleType hapiGetApprovedType = TupleType.parse("(int32,bytes32)");
    public static final TupleType getTokenExpiryInfoType = TupleType.parse(RESPONSE_STATUS_AT_BEGINNING + EXPIRY + ")");
    public static final TupleType getTokenKeyType = TupleType.parse(RESPONSE_STATUS_AT_BEGINNING + KEY_VALUE + ")");
    public static final TupleType notSpecifiedType = TupleType.parse(INT32);

    public enum FunctionType {
        ERC_TOTAL_SUPPLY,
        ERC_DECIMALS,
        ERC_BALANCE,
        ERC_OWNER,
        ERC_TOKEN_URI,
        ERC_NAME,
        ERC_SYMBOL,
        ERC_TRANSFER,
        ERC_ALLOWANCE,
        ERC_APPROVE,
        ERC_GET_APPROVED,
        ERC_IS_APPROVED_FOR_ALL,
        HAPI_CREATE,
        HAPI_MINT,
        HAPI_BURN,
        HAPI_ALLOWANCE,
        HAPI_APPROVE,
        HAPI_TRANSFER_FROM,
        HAPI_APPROVE_NFT,
        HAPI_TRANSFER_FROM_NFT,
        HAPI_GET_APPROVED,
        HAPI_GET_FUNGIBLE_TOKEN_INFO,
        HAPI_GET_FUNGIBLE_TOKEN_INFO_V2,
        HAPI_GET_TOKEN_INFO,
        HAPI_GET_TOKEN_INFO_V2,
        HAPI_GET_NON_FUNGIBLE_TOKEN_INFO,
        HAPI_GET_NON_FUNGIBLE_TOKEN_INFO_V2,
        HAPI_IS_APPROVED_FOR_ALL,
        HAPI_IS_KYC,
        GET_TOKEN_DEFAULT_FREEZE_STATUS,
        GET_TOKEN_DEFAULT_KYC_STATUS,
        HAPI_IS_FROZEN,
        HAPI_GET_TOKEN_CUSTOM_FEES,
        HAPI_IS_TOKEN,
        HAPI_GET_TOKEN_TYPE,
        HAPI_GET_TOKEN_EXPIRY_INFO,
        HAPI_GET_TOKEN_KEY,
        NOT_SPECIFIED
    }
}
