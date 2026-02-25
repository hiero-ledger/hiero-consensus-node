// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.precompile;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.*;
import static com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil.asHeadlongAddress;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.*;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.TOKEN_TREASURY;
import static com.hedera.services.bdd.suites.contract.Utils.asAddress;
import static com.hedera.services.bdd.suites.utils.contracts.precompile.HTSPrecompileResult.htsPrecompileResult;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.*;

import com.hedera.node.app.hapi.utils.contracts.ParsingConstants;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil;
import com.hederahashgraph.api.proto.java.TokenID;
import com.hederahashgraph.api.proto.java.TokenType;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
public class RedirectPrecompileSuite {
    private static final String FUNGIBLE_TOKEN = "fungibleToken";
    private static final String MULTI_KEY = "purpose";
    private static final String ACCOUNT = "anybody";
    private static final String CONTRACT = "RedirectTestContract";
    private static final String NULL_CONTRACT = "RedirectNullContract";
    private static final String TXN = "txn";

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> balanceOf() {
        final var totalSupply = 50;
        return hapiTest(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(ACCOUNT).balance(100 * ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(totalSupply)
                        .treasury(TOKEN_TREASURY)
                        .adminKey(MULTI_KEY)
                        .supplyKey(MULTI_KEY),
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        CONTRACT,
                                        "getBalanceOf",
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(FUNGIBLE_TOKEN))),
                                        asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(TOKEN_TREASURY))))
                                .payingWith(ACCOUNT)
                                .via(TXN)
                                .hasKnownStatus(SUCCESS)
                                .gas(1_000_000))),
                childRecordsCheck(
                        TXN,
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .forFunction(ParsingConstants.FunctionType.ERC_BALANCE)
                                                .withBalance(totalSupply))
                                        .gasUsed(2607L))));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> redirectToInvalidToken() {
        return hapiTest(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(ACCOUNT).balance(100 * ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY).balance(100 * ONE_HUNDRED_HBARS),
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        CONTRACT,
                                        "getBalanceOf",
                                        asHeadlongAddress(asAddress(TokenID.newBuilder()
                                                .setTokenNum(spec.registry()
                                                                .getContractId(CONTRACT)
                                                                .getContractNum()
                                                        + 5_555_555)
                                                .build())),
                                        asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(TOKEN_TREASURY))))
                                .payingWith(ACCOUNT)
                                .via(TXN)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                .gas(1_000_000))),
                childRecordsCheck(
                        TXN,
                        CONTRACT_REVERT_EXECUTED,
                        recordWith()
                                .status(INVALID_TOKEN_ID)
                                .contractCallResult(resultWith().gasUsed(2607L))));
    }

    @HapiTest
    final Stream<DynamicTest> redirectToNullSelector() {
        return hapiTest(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(ACCOUNT).balance(100 * ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY).balance(100 * ONE_HUNDRED_HBARS),
                uploadInitCode(NULL_CONTRACT),
                contractCreate(NULL_CONTRACT),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        NULL_CONTRACT,
                                        "sendNullSelector",
                                        asHeadlongAddress(asAddress(TokenID.newBuilder()
                                                .setTokenNum(spec.registry()
                                                                .getContractId(NULL_CONTRACT)
                                                                .getContractNum()
                                                        + 5_555_555)
                                                .build())))
                                .payingWith(ACCOUNT)
                                .via(TXN)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                .gas(1_000_000))));
    }
}
