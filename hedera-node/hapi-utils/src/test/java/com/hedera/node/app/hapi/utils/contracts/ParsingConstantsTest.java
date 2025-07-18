// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.contracts;

import static com.hedera.node.app.hapi.utils.contracts.ParsingConstants.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.esaulpaugh.headlong.abi.TupleType;
import com.hedera.node.app.hapi.utils.contracts.ParsingConstants.FunctionType;
import org.junit.jupiter.api.Test;

class ParsingConstantsTest {

    @Test
    void functionTypeValidation() {
        assertEquals("ERC_ALLOWANCE", FunctionType.ERC_ALLOWANCE.name());
        assertEquals("ERC_APPROVE", FunctionType.ERC_APPROVE.name());
        assertEquals("ERC_BALANCE", FunctionType.ERC_BALANCE.name());
        assertEquals("ERC_IS_APPROVED_FOR_ALL", FunctionType.ERC_IS_APPROVED_FOR_ALL.name());
        assertEquals("ERC_DECIMALS", FunctionType.ERC_DECIMALS.name());
        assertEquals("ERC_TRANSFER", FunctionType.ERC_TRANSFER.name());
        assertEquals("ERC_GET_APPROVED", FunctionType.ERC_GET_APPROVED.name());
        assertEquals("ERC_OWNER", FunctionType.ERC_OWNER.name());
        assertEquals("ERC_SYMBOL", FunctionType.ERC_SYMBOL.name());
        assertEquals("ERC_TOTAL_SUPPLY", FunctionType.ERC_TOTAL_SUPPLY.name());
        assertEquals("ERC_TOKEN_URI", FunctionType.ERC_TOKEN_URI.name());
        assertEquals("ERC_NAME", FunctionType.ERC_NAME.name());
        assertEquals("HAPI_GET_FUNGIBLE_TOKEN_INFO", FunctionType.HAPI_GET_FUNGIBLE_TOKEN_INFO.name());
        assertEquals("HAPI_GET_NON_FUNGIBLE_TOKEN_INFO", FunctionType.HAPI_GET_NON_FUNGIBLE_TOKEN_INFO.name());
        assertEquals("HAPI_GET_TOKEN_INFO", FunctionType.HAPI_GET_TOKEN_INFO.name());
        assertEquals("HAPI_GET_TOKEN_CUSTOM_FEES", FunctionType.HAPI_GET_TOKEN_CUSTOM_FEES.name());
        assertEquals("HAPI_MINT", FunctionType.HAPI_MINT.name());
        assertEquals("HAPI_BURN", FunctionType.HAPI_BURN.name());
        assertEquals("HAPI_CREATE", FunctionType.HAPI_CREATE.name());
        assertEquals("HAPI_ALLOWANCE", FunctionType.HAPI_ALLOWANCE.name());
        assertEquals("HAPI_APPROVE_NFT", FunctionType.HAPI_APPROVE_NFT.name());
        assertEquals("HAPI_APPROVE", FunctionType.HAPI_APPROVE.name());
        assertEquals("HAPI_GET_APPROVED", FunctionType.HAPI_GET_APPROVED.name());
        assertEquals("HAPI_IS_APPROVED_FOR_ALL", FunctionType.HAPI_IS_APPROVED_FOR_ALL.name());
        assertEquals("NOT_SPECIFIED", FunctionType.NOT_SPECIFIED.name());
    }

    @Test
    void tupleTypesValidation() {
        assertEquals(bigIntegerTuple, TupleType.parse(UINT256));
        assertEquals(booleanTuple, TupleType.parse(BOOL));
        assertEquals(decimalsType, TupleType.parse(UINT8));
        assertEquals(addressTuple, TupleType.parse(ADDRESS));
        assertEquals(stringTuple, TupleType.parse(STRING));
        assertEquals(notSpecifiedType, TupleType.parse(INT32));
        assertEquals(intBoolTuple, TupleType.parse(INT_BOOL_PAIR_RETURN_TYPE));
        assertEquals(burnReturnType, TupleType.parse("(int32,uint64)"));
        assertEquals(intAddressTuple, TupleType.parse("(int32,address)"));
        assertEquals(mintReturnType, TupleType.parse("(int32,uint64,int64[])"));
        assertEquals(hapiAllowanceOfType, TupleType.parse("(int32,uint256)"));
        assertEquals(hapiGetApprovedType, TupleType.parse("(int32,bytes32)"));
        assertEquals(
                getFungibleTokenInfoType, TupleType.parse(RESPONSE_STATUS_AT_BEGINNING + FUNGIBLE_TOKEN_INFO + ")"));
        assertEquals(getTokenInfoType, TupleType.parse(RESPONSE_STATUS_AT_BEGINNING + TOKEN_INFO + ")"));
        assertEquals(
                getNonFungibleTokenInfoType,
                TupleType.parse(RESPONSE_STATUS_AT_BEGINNING + NON_FUNGIBLE_TOKEN_INFO + ")"));
        assertEquals(
                getTokenCustomFeesType,
                TupleType.parse(RESPONSE_STATUS_AT_BEGINNING
                        + FIXED_FEE
                        + "[],"
                        + FRACTIONAL_FEE
                        + "[],"
                        + ROYALTY_FEE
                        + "[])"));
    }
}
