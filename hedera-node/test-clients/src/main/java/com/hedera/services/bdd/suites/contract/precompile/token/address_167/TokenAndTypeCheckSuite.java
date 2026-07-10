// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.precompile.token.address_167;

import static com.hedera.node.app.hapi.utils.contracts.ParsingConstants.FunctionType.HAPI_IS_TOKEN;
import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.isLiteralResult;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.dsl.entities.SpecContract.VARIANT_167;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.contractCallLocal;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.childRecordsCheck;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.TOKEN_TREASURY;
import static com.hedera.services.bdd.suites.contract.Utils.asAddress;
import static com.hedera.services.bdd.suites.contract.Utils.asToken;
import static com.hedera.services.bdd.suites.token.TokenAssociationSpecs.VANILLA_TOKEN;
import static com.hedera.services.bdd.suites.utils.contracts.precompile.HTSPrecompileResult.htsPrecompileResult;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil;
import com.hederahashgraph.api.proto.java.TokenID;
import java.math.BigInteger;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
public class TokenAndTypeCheckSuite {
    private static final String TOKEN_AND_TYPE_CHECK_CONTRACT = "TokenAndTypeCheck";
    private static final String ACCOUNT = "anybody";
    private static final int GAS_TO_OFFER = 1_000_000;
    private static final String GET_TOKEN_TYPE = "getType";
    private static final String IS_TOKEN = "isAToken";

    @HapiTest
    final Stream<DynamicTest> checkTokenAndTypeStandardCases() {
        final AtomicReference<TokenID> vanillaTokenID = new AtomicReference<>();

        return hapiTest(
                cryptoCreate(ACCOUNT).balance(100 * ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(VANILLA_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .treasury(TOKEN_TREASURY)
                        .initialSupply(1_000)
                        .exposingCreatedIdTo(id -> vanillaTokenID.set(asToken(id))),
                tokenAssociate(ACCOUNT, VANILLA_TOKEN),
                uploadInitCode(Optional.empty(), VARIANT_167, TOKEN_AND_TYPE_CHECK_CONTRACT),
                contractCreate(TOKEN_AND_TYPE_CHECK_CONTRACT),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCallLocal(
                                        Optional.of(VARIANT_167),
                                        TOKEN_AND_TYPE_CHECK_CONTRACT,
                                        IS_TOKEN,
                                        HapiParserUtil.asHeadlongAddress(asAddress(vanillaTokenID.get())))
                                .logged()
                                .has(resultWith()
                                        .resultViaFunctionName(
                                                Optional.of(VARIANT_167),
                                                IS_TOKEN,
                                                TOKEN_AND_TYPE_CHECK_CONTRACT,
                                                isLiteralResult(new Object[] {Boolean.TRUE}))),
                        contractCallLocal(
                                        Optional.of(VARIANT_167),
                                        TOKEN_AND_TYPE_CHECK_CONTRACT,
                                        GET_TOKEN_TYPE,
                                        HapiParserUtil.asHeadlongAddress(asAddress(vanillaTokenID.get())))
                                .logged()
                                .has(resultWith()
                                        .resultViaFunctionName(
                                                Optional.of(VARIANT_167),
                                                GET_TOKEN_TYPE,
                                                TOKEN_AND_TYPE_CHECK_CONTRACT,
                                                isLiteralResult(new Object[] {BigInteger.valueOf(0)}))))));
    }

    // Should just return false on isToken() check for missing token type
    @HapiTest
    final Stream<DynamicTest> checkTokenAndTypeNegativeCases() {
        final AtomicReference<TokenID> vanillaTokenID = new AtomicReference<>();
        final var notAnAddress = new byte[20];

        return hapiTest(
                cryptoCreate(ACCOUNT).balance(100 * ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(VANILLA_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .treasury(TOKEN_TREASURY)
                        .initialSupply(1_000)
                        .exposingCreatedIdTo(id -> vanillaTokenID.set(asToken(id))),
                tokenAssociate(ACCOUNT, VANILLA_TOKEN),
                uploadInitCode(Optional.empty(), VARIANT_167, TOKEN_AND_TYPE_CHECK_CONTRACT),
                contractCreate(TOKEN_AND_TYPE_CHECK_CONTRACT),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        Optional.of(VARIANT_167),
                                        TOKEN_AND_TYPE_CHECK_CONTRACT,
                                        IS_TOKEN,
                                        HapiParserUtil.asHeadlongAddress(notAnAddress))
                                .via("FakeAddressTokenCheckTx")
                                .payingWith(ACCOUNT)
                                .gas(GAS_TO_OFFER),
                        contractCall(
                                        Optional.of(VARIANT_167),
                                        TOKEN_AND_TYPE_CHECK_CONTRACT,
                                        GET_TOKEN_TYPE,
                                        HapiParserUtil.asHeadlongAddress(notAnAddress))
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                .via("FakeAddressTokenTypeCheckTx")
                                .payingWith(ACCOUNT)
                                .gas(GAS_TO_OFFER)
                                .logged())),
                childRecordsCheck(
                        "FakeAddressTokenCheckTx",
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .forFunction(HAPI_IS_TOKEN)
                                                .withStatus(SUCCESS)
                                                .withIsToken(false)))),
                childRecordsCheck(
                        "FakeAddressTokenTypeCheckTx",
                        CONTRACT_REVERT_EXECUTED,
                        recordWith().status(INVALID_TOKEN_ID)
                        //                                        .contractCallResult(resultWith()
                        //
                        // .contractCallResult(htsPrecompileResult()
                        //                                                        .forFunction(HAPI_IS_TOKEN)
                        //                                                        .withStatus(INVALID_TOKEN_ID)
                        //                                                        .withIsToken(false)))
                        ));
    }
}
