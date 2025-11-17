// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.precompile;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.contractCallLocal;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.grantTokenKyc;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.childRecordsCheck;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.TOKEN_TREASURY;
import static com.hedera.services.bdd.suites.contract.Utils.asAddress;
import static com.hedera.services.bdd.suites.contract.Utils.asToken;
import static com.hedera.services.bdd.suites.token.TokenAssociationSpecs.VANILLA_TOKEN;
import static com.hedera.services.bdd.suites.utils.contracts.precompile.HTSPrecompileResult.htsPrecompileResult;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_HAS_NO_KYC_KEY;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;

import com.hedera.node.app.hapi.utils.contracts.ParsingConstants.FunctionType;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil;
import com.hederahashgraph.api.proto.java.TokenID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
public class DefaultTokenStatusSuite {
    private static final String TOKEN_DEFAULT_KYC_FREEZE_STATUS_CONTRACT = "TokenDefaultKycAndFreezeStatus";
    private static final String ACCOUNT = "anybody";
    private static final String KYC_KEY = "kycKey";
    private static final String FREEZE_KEY = "freezeKey";
    private static final int GAS_TO_OFFER = 1_000_000;
    private static final String GET_TOKEN_DEFAULT_FREEZE = "getTokenDefaultFreeze";
    private static final String GET_TOKEN_DEFAULT_KYC = "getTokenDefaultKyc";

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> getTokenDefaultFreezeStatus() {
        final AtomicReference<TokenID> vanillaTokenID = new AtomicReference<>();

        return hapiTest(
                newKeyNamed(FREEZE_KEY),
                cryptoCreate(ACCOUNT).balance(100 * ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(VANILLA_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .treasury(TOKEN_TREASURY)
                        .freezeDefault(true)
                        .freezeKey(FREEZE_KEY)
                        .initialSupply(1_000)
                        .exposingCreatedIdTo(id -> vanillaTokenID.set(asToken(id))),
                uploadInitCode(TOKEN_DEFAULT_KYC_FREEZE_STATUS_CONTRACT),
                contractCreate(TOKEN_DEFAULT_KYC_FREEZE_STATUS_CONTRACT),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        TOKEN_DEFAULT_KYC_FREEZE_STATUS_CONTRACT,
                                        GET_TOKEN_DEFAULT_FREEZE,
                                        HapiParserUtil.asHeadlongAddress(asAddress(vanillaTokenID.get())))
                                .payingWith(ACCOUNT)
                                .via("GetTokenDefaultFreezeStatusTx")
                                .gas(GAS_TO_OFFER),
                        contractCallLocal(
                                TOKEN_DEFAULT_KYC_FREEZE_STATUS_CONTRACT,
                                GET_TOKEN_DEFAULT_FREEZE,
                                HapiParserUtil.asHeadlongAddress(asAddress(vanillaTokenID.get()))))),
                childRecordsCheck(
                        "GetTokenDefaultFreezeStatusTx",
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .forFunction(FunctionType.GET_TOKEN_DEFAULT_FREEZE_STATUS)
                                                .withStatus(SUCCESS)
                                                .withTokenDefaultFreezeStatus(true)))));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> getTokenDefaultKycStatus() {
        final AtomicReference<TokenID> vanillaTokenID = new AtomicReference<>();
        final AtomicReference<TokenID> noKycTokenId = new AtomicReference<>();
        final var notAnAddress = new byte[20];

        return hapiTest(
                newKeyNamed(KYC_KEY),
                cryptoCreate(ACCOUNT).balance(100 * ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(VANILLA_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .treasury(TOKEN_TREASURY)
                        .kycKey(KYC_KEY)
                        .initialSupply(1_000)
                        .exposingCreatedIdTo(id -> vanillaTokenID.set(asToken(id))),
                tokenAssociate(ACCOUNT, VANILLA_TOKEN),
                grantTokenKyc(VANILLA_TOKEN, ACCOUNT),
                tokenCreate("noKycToken")
                        .tokenType(FUNGIBLE_COMMON)
                        .treasury(TOKEN_TREASURY)
                        .initialSupply(1_000)
                        .exposingCreatedIdTo(id -> noKycTokenId.set(asToken(id))),
                tokenAssociate(ACCOUNT, "noKycToken"),
                grantTokenKyc("noKycToken", ACCOUNT).hasKnownStatus(TOKEN_HAS_NO_KYC_KEY),
                uploadInitCode(TOKEN_DEFAULT_KYC_FREEZE_STATUS_CONTRACT),
                contractCreate(TOKEN_DEFAULT_KYC_FREEZE_STATUS_CONTRACT),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        TOKEN_DEFAULT_KYC_FREEZE_STATUS_CONTRACT,
                                        GET_TOKEN_DEFAULT_KYC,
                                        HapiParserUtil.asHeadlongAddress(asAddress(vanillaTokenID.get())))
                                .payingWith(ACCOUNT)
                                .via("GetTokenDefaultKycStatusTx")
                                .gas(GAS_TO_OFFER),
                        contractCall(
                                        TOKEN_DEFAULT_KYC_FREEZE_STATUS_CONTRACT,
                                        GET_TOKEN_DEFAULT_KYC,
                                        HapiParserUtil.asHeadlongAddress(asAddress(noKycTokenId.get())))
                                .payingWith(ACCOUNT)
                                .via("defaultKycStatus")
                                .gas(GAS_TO_OFFER),
                        contractCallLocal(
                                TOKEN_DEFAULT_KYC_FREEZE_STATUS_CONTRACT,
                                GET_TOKEN_DEFAULT_KYC,
                                HapiParserUtil.asHeadlongAddress(asAddress(vanillaTokenID.get()))))),
                childRecordsCheck(
                        "GetTokenDefaultKycStatusTx",
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .forFunction(FunctionType.GET_TOKEN_DEFAULT_KYC_STATUS)
                                                .withStatus(SUCCESS)
                                                .withTokenDefaultKycStatus(false)))),
                childRecordsCheck(
                        "defaultKycStatus",
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .forFunction(FunctionType.GET_TOKEN_DEFAULT_KYC_STATUS)
                                                .withStatus(SUCCESS)
                                                .withTokenDefaultKycStatus(true)))));
    }
}
