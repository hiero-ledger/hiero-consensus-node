// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.precompile;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.keys.KeyShape.CONTRACT;
import static com.hedera.services.bdd.spec.keys.KeyShape.ED25519;
import static com.hedera.services.bdd.spec.keys.KeyShape.sigs;
import static com.hedera.services.bdd.spec.keys.SigControl.ON;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUnpause;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil.asHeadlongAddress;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.childRecordsCheck;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.TOKEN_TREASURY;
import static com.hedera.services.bdd.suites.contract.Utils.asHexedAddress;
import static com.hedera.services.bdd.suites.contract.Utils.asToken;
import static com.hedera.services.bdd.suites.token.TokenAssociationSpecs.MULTI_KEY;
import static com.hedera.services.bdd.suites.token.TokenAssociationSpecs.VANILLA_TOKEN;
import static com.hedera.services.bdd.suites.utils.contracts.precompile.HTSPrecompileResult.htsPrecompileResult;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_HAS_NO_PAUSE_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_WAS_DELETED;
import static com.hederahashgraph.api.proto.java.TokenPauseStatus.Paused;
import static com.hederahashgraph.api.proto.java.TokenPauseStatus.Unpaused;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.TokenID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
public class PauseUnpauseTokenAccountPrecompileSuite {
    public static final String PAUSE_UNPAUSE_CONTRACT = "PauseUnpauseTokenAccount";

    private static final String PAUSE_FUNGIBLE_TXN = "pauseFungibleTxn";
    private static final String UNPAUSE_FUNGIBLE_TXN = "unpauseFungibleTxn";
    private static final String PAUSE_NONFUNGIBLE_TXN = "pauseNonFungibleTxn";
    private static final String UNPAUSE_NONFUNGIBLE_TXN = "unpauseNonFungibleTxn";
    private static final String UNPAUSE_KEY = "UNPAUSE_KEY";
    private static final String PAUSE_KEY = "PAUSE_KEY";

    public static final String THRESHOLD_KEY = "ThreshKey";
    private static final KeyShape THRESHOLD_KEY_SHAPE = KeyShape.threshOf(1, ED25519, CONTRACT);

    private static final String ACCOUNT = "account";
    private static final String TREASURY = "treasury";

    public static final long INITIAL_BALANCE = 1_000_000_000L;
    private static final long GAS_TO_OFFER = 4_000_000L;
    public static final String PAUSE_TOKEN_ACCOUNT_FUNCTION_NAME = "pauseTokenAccount";
    public static final String UNPAUSE_TOKEN_ACCOUNT_FUNCTION_NAME = "unpauseTokenAccount";
    private static final String INVALID_ADDRESS = "0x0000000000000000000000000000000000123456";
    public static final String UNPAUSE_TX = "UnpauseTx";
    public static final String PAUSE_TX = "PauseTx";

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> pauseFungibleToken() {
        final AtomicReference<TokenID> vanillaTokenID = new AtomicReference<>();
        return hapiTest(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(TOKEN_TREASURY),
                cryptoCreate(ACCOUNT).balance(INITIAL_BALANCE),
                tokenCreate(VANILLA_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .treasury(TOKEN_TREASURY)
                        .pauseKey(MULTI_KEY)
                        .adminKey(MULTI_KEY)
                        .initialSupply(1_000)
                        .exposingCreatedIdTo(id -> vanillaTokenID.set(asToken(id))),
                uploadInitCode(PAUSE_UNPAUSE_CONTRACT),
                contractCreate(PAUSE_UNPAUSE_CONTRACT),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        PAUSE_UNPAUSE_CONTRACT,
                                        PAUSE_TOKEN_ACCOUNT_FUNCTION_NAME,
                                        asHeadlongAddress(asHexedAddress(vanillaTokenID.get())))
                                .signedBy(GENESIS, ACCOUNT)
                                .alsoSigningWithFullPrefix(ACCOUNT)
                                .via("pauseFungibleAccountDoesNotOwnPauseKeyFailingTxn")
                                .gas(GAS_TO_OFFER)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                        newKeyNamed(THRESHOLD_KEY)
                                .shape(THRESHOLD_KEY_SHAPE.signedWith(sigs(ON, PAUSE_UNPAUSE_CONTRACT))),
                        tokenUpdate(VANILLA_TOKEN).pauseKey(THRESHOLD_KEY).signedByPayerAnd(MULTI_KEY),
                        cryptoUpdate(ACCOUNT).key(THRESHOLD_KEY),
                        contractCall(
                                        PAUSE_UNPAUSE_CONTRACT,
                                        PAUSE_TOKEN_ACCOUNT_FUNCTION_NAME,
                                        asHeadlongAddress(asHexedAddress(vanillaTokenID.get())))
                                .signedBy(GENESIS, ACCOUNT)
                                .alsoSigningWithFullPrefix(ACCOUNT)
                                .via(PAUSE_FUNGIBLE_TXN)
                                .gas(GAS_TO_OFFER),
                        getTokenInfo(VANILLA_TOKEN).hasPauseStatus(Paused),
                        tokenUnpause(VANILLA_TOKEN),
                        tokenDelete(VANILLA_TOKEN),
                        contractCall(
                                        PAUSE_UNPAUSE_CONTRACT,
                                        PAUSE_TOKEN_ACCOUNT_FUNCTION_NAME,
                                        asHeadlongAddress(asHexedAddress(vanillaTokenID.get())))
                                .signedBy(GENESIS, ACCOUNT)
                                .alsoSigningWithFullPrefix(ACCOUNT)
                                .via("pauseFungibleAccountIsDeletedFailingTxn")
                                .gas(GAS_TO_OFFER)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED))),
                childRecordsCheck(
                        "pauseFungibleAccountDoesNotOwnPauseKeyFailingTxn",
                        CONTRACT_REVERT_EXECUTED,
                        recordWith()
                                .status(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .withStatus(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)))),
                childRecordsCheck(
                        "pauseFungibleAccountIsDeletedFailingTxn",
                        CONTRACT_REVERT_EXECUTED,
                        recordWith()
                                .status(TOKEN_WAS_DELETED)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(TOKEN_WAS_DELETED)))));
    }

    @HapiTest
    final Stream<DynamicTest> unpauseFungibleToken() {
        final AtomicReference<TokenID> vanillaTokenID = new AtomicReference<>();
        return hapiTest(
                newKeyNamed(UNPAUSE_KEY),
                cryptoCreate(TOKEN_TREASURY),
                cryptoCreate(ACCOUNT).balance(INITIAL_BALANCE),
                tokenCreate(VANILLA_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .treasury(TOKEN_TREASURY)
                        .pauseKey(UNPAUSE_KEY)
                        .adminKey(UNPAUSE_KEY)
                        .initialSupply(1_000)
                        .exposingCreatedIdTo(id -> vanillaTokenID.set(asToken(id))),
                uploadInitCode(PAUSE_UNPAUSE_CONTRACT),
                contractCreate(PAUSE_UNPAUSE_CONTRACT),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        PAUSE_UNPAUSE_CONTRACT,
                                        UNPAUSE_TOKEN_ACCOUNT_FUNCTION_NAME,
                                        asHeadlongAddress(asHexedAddress(vanillaTokenID.get())))
                                .signedBy(GENESIS, ACCOUNT)
                                .alsoSigningWithFullPrefix(ACCOUNT)
                                .via("unpauseFungibleAccountDoesNotOwnPauseKeyFailingTxn")
                                .gas(GAS_TO_OFFER)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                        newKeyNamed(THRESHOLD_KEY)
                                .shape(THRESHOLD_KEY_SHAPE.signedWith(sigs(ON, PAUSE_UNPAUSE_CONTRACT))),
                        tokenUpdate(VANILLA_TOKEN).pauseKey(THRESHOLD_KEY).signedByPayerAnd(UNPAUSE_KEY),
                        cryptoUpdate(ACCOUNT).key(THRESHOLD_KEY),
                        contractCall(
                                        PAUSE_UNPAUSE_CONTRACT,
                                        UNPAUSE_TOKEN_ACCOUNT_FUNCTION_NAME,
                                        asHeadlongAddress(asHexedAddress(vanillaTokenID.get())))
                                .signedBy(GENESIS, ACCOUNT)
                                .alsoSigningWithFullPrefix(ACCOUNT)
                                .via(UNPAUSE_FUNGIBLE_TXN)
                                .gas(GAS_TO_OFFER))),
                childRecordsCheck(
                        "unpauseFungibleAccountDoesNotOwnPauseKeyFailingTxn",
                        CONTRACT_REVERT_EXECUTED,
                        recordWith()
                                .status(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .withStatus(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)))),
                getTokenInfo(VANILLA_TOKEN).hasPauseStatus(Unpaused));
    }

    @HapiTest
    final Stream<DynamicTest> pauseNonFungibleToken() {
        final AtomicReference<TokenID> vanillaTokenID = new AtomicReference<>();
        return hapiTest(
                newKeyNamed(MULTI_KEY),
                newKeyNamed(PAUSE_KEY),
                cryptoCreate(TOKEN_TREASURY),
                cryptoCreate(ACCOUNT).balance(INITIAL_BALANCE),
                tokenCreate(VANILLA_TOKEN)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .treasury(TOKEN_TREASURY)
                        .adminKey(MULTI_KEY)
                        .pauseKey(PAUSE_KEY)
                        .supplyKey(MULTI_KEY)
                        .initialSupply(0)
                        .exposingCreatedIdTo(id -> vanillaTokenID.set(asToken(id))),
                uploadInitCode(PAUSE_UNPAUSE_CONTRACT),
                contractCreate(PAUSE_UNPAUSE_CONTRACT),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        PAUSE_UNPAUSE_CONTRACT,
                                        PAUSE_TOKEN_ACCOUNT_FUNCTION_NAME,
                                        asHeadlongAddress(asHexedAddress(vanillaTokenID.get())))
                                .signedBy(GENESIS, ACCOUNT)
                                .alsoSigningWithFullPrefix(ACCOUNT)
                                .via("pauseNonFungibleAccountDoesNotOwnPauseKeyFailingTxn")
                                .gas(GAS_TO_OFFER)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                        newKeyNamed(THRESHOLD_KEY)
                                .shape(THRESHOLD_KEY_SHAPE.signedWith(sigs(ON, PAUSE_UNPAUSE_CONTRACT))),
                        tokenUpdate(VANILLA_TOKEN).pauseKey(THRESHOLD_KEY).signedByPayerAnd(MULTI_KEY),
                        cryptoUpdate(ACCOUNT).key(THRESHOLD_KEY),
                        contractCall(
                                        PAUSE_UNPAUSE_CONTRACT,
                                        PAUSE_TOKEN_ACCOUNT_FUNCTION_NAME,
                                        asHeadlongAddress(asHexedAddress(vanillaTokenID.get())))
                                .signedBy(GENESIS, ACCOUNT)
                                .alsoSigningWithFullPrefix(ACCOUNT)
                                .via(PAUSE_NONFUNGIBLE_TXN)
                                .gas(GAS_TO_OFFER),
                        getTokenInfo(VANILLA_TOKEN).hasPauseStatus(Paused),
                        tokenUnpause(VANILLA_TOKEN),
                        tokenDelete(VANILLA_TOKEN),
                        contractCall(
                                        PAUSE_UNPAUSE_CONTRACT,
                                        PAUSE_TOKEN_ACCOUNT_FUNCTION_NAME,
                                        asHeadlongAddress(asHexedAddress(vanillaTokenID.get())))
                                .signedBy(GENESIS, ACCOUNT)
                                .alsoSigningWithFullPrefix(ACCOUNT)
                                .via("pauseNonFungibleAccountIsDeletedFailingTxn")
                                .gas(GAS_TO_OFFER)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED))),
                childRecordsCheck(
                        "pauseNonFungibleAccountDoesNotOwnPauseKeyFailingTxn",
                        CONTRACT_REVERT_EXECUTED,
                        recordWith()
                                .status(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .withStatus(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)))),
                childRecordsCheck(
                        "pauseNonFungibleAccountIsDeletedFailingTxn",
                        CONTRACT_REVERT_EXECUTED,
                        recordWith()
                                .status(TOKEN_WAS_DELETED)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(TOKEN_WAS_DELETED)))));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> unpauseNonFungibleToken() {
        final AtomicReference<TokenID> vanillaTokenID = new AtomicReference<>();
        return hapiTest(
                newKeyNamed(MULTI_KEY),
                newKeyNamed(UNPAUSE_KEY),
                cryptoCreate(TOKEN_TREASURY),
                cryptoCreate(ACCOUNT).balance(INITIAL_BALANCE),
                tokenCreate(VANILLA_TOKEN)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .treasury(TOKEN_TREASURY)
                        .pauseKey(UNPAUSE_KEY)
                        .supplyKey(MULTI_KEY)
                        .adminKey(UNPAUSE_KEY)
                        .initialSupply(0)
                        .exposingCreatedIdTo(id -> vanillaTokenID.set(asToken(id))),
                uploadInitCode(PAUSE_UNPAUSE_CONTRACT),
                contractCreate(PAUSE_UNPAUSE_CONTRACT),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        PAUSE_UNPAUSE_CONTRACT,
                                        UNPAUSE_TOKEN_ACCOUNT_FUNCTION_NAME,
                                        asHeadlongAddress(asHexedAddress(vanillaTokenID.get())))
                                .signedBy(GENESIS, ACCOUNT)
                                .alsoSigningWithFullPrefix(ACCOUNT)
                                .via("unpauseNonFungibleAccountDoesNotOwnPauseKeyFailingTxn")
                                .gas(GAS_TO_OFFER)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                        newKeyNamed(THRESHOLD_KEY)
                                .shape(THRESHOLD_KEY_SHAPE.signedWith(sigs(ON, PAUSE_UNPAUSE_CONTRACT))),
                        tokenUpdate(VANILLA_TOKEN).pauseKey(THRESHOLD_KEY).signedByPayerAnd(UNPAUSE_KEY),
                        cryptoUpdate(ACCOUNT).key(THRESHOLD_KEY),
                        contractCall(
                                        PAUSE_UNPAUSE_CONTRACT,
                                        UNPAUSE_TOKEN_ACCOUNT_FUNCTION_NAME,
                                        asHeadlongAddress(asHexedAddress(vanillaTokenID.get())))
                                .signedBy(GENESIS, ACCOUNT)
                                .alsoSigningWithFullPrefix(ACCOUNT)
                                .via(UNPAUSE_NONFUNGIBLE_TXN)
                                .gas(GAS_TO_OFFER))),
                childRecordsCheck(
                        "unpauseNonFungibleAccountDoesNotOwnPauseKeyFailingTxn",
                        CONTRACT_REVERT_EXECUTED,
                        recordWith()
                                .status(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .withStatus(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)))),
                getTokenInfo(VANILLA_TOKEN).hasPauseStatus(Unpaused));
    }

    @HapiTest
    final Stream<DynamicTest> noTokenIdReverts() {
        final AtomicReference<TokenID> vanillaTokenID = new AtomicReference<>();
        return hapiTest(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(TOKEN_TREASURY),
                cryptoCreate(ACCOUNT).balance(INITIAL_BALANCE),
                tokenCreate(VANILLA_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .treasury(TOKEN_TREASURY)
                        .pauseKey(MULTI_KEY)
                        .adminKey(MULTI_KEY)
                        .initialSupply(1_000)
                        .exposingCreatedIdTo(id -> vanillaTokenID.set(asToken(id))),
                uploadInitCode(PAUSE_UNPAUSE_CONTRACT),
                contractCreate(PAUSE_UNPAUSE_CONTRACT),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        PAUSE_UNPAUSE_CONTRACT,
                                        PAUSE_TOKEN_ACCOUNT_FUNCTION_NAME,
                                        asHeadlongAddress(INVALID_ADDRESS))
                                .payingWith(ACCOUNT)
                                .gas(GAS_TO_OFFER)
                                .via(PAUSE_TX)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                        contractCall(
                                        PAUSE_UNPAUSE_CONTRACT,
                                        UNPAUSE_TOKEN_ACCOUNT_FUNCTION_NAME,
                                        asHeadlongAddress(INVALID_ADDRESS))
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                .payingWith(ACCOUNT)
                                .gas(GAS_TO_OFFER)
                                .via(UNPAUSE_TX))),
                childRecordsCheck(
                        PAUSE_TX, CONTRACT_REVERT_EXECUTED, recordWith().status(INVALID_TOKEN_ID)),
                childRecordsCheck(
                        UNPAUSE_TX, CONTRACT_REVERT_EXECUTED, recordWith().status(INVALID_TOKEN_ID)));
    }

    @HapiTest
    final Stream<DynamicTest> noAccountKeyReverts() {
        final AtomicReference<AccountID> accountID = new AtomicReference<>();
        final AtomicReference<TokenID> vanillaTokenID = new AtomicReference<>();
        return hapiTest(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(ACCOUNT).balance(100 * ONE_HBAR).exposingCreatedIdTo(accountID::set),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(VANILLA_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .treasury(TOKEN_TREASURY)
                        .freezeKey(MULTI_KEY)
                        .initialSupply(1_000)
                        .exposingCreatedIdTo(id -> vanillaTokenID.set(asToken(id))),
                uploadInitCode(PAUSE_UNPAUSE_CONTRACT),
                contractCreate(PAUSE_UNPAUSE_CONTRACT),
                tokenAssociate(ACCOUNT, VANILLA_TOKEN),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        PAUSE_UNPAUSE_CONTRACT,
                                        PAUSE_TOKEN_ACCOUNT_FUNCTION_NAME,
                                        asHeadlongAddress(asHexedAddress(vanillaTokenID.get())))
                                .payingWith(ACCOUNT)
                                .gas(GAS_TO_OFFER)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                .via(PAUSE_TX),
                        contractCall(
                                        PAUSE_UNPAUSE_CONTRACT,
                                        UNPAUSE_TOKEN_ACCOUNT_FUNCTION_NAME,
                                        asHeadlongAddress(asHexedAddress(vanillaTokenID.get())))
                                .payingWith(ACCOUNT)
                                .gas(GAS_TO_OFFER)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                .via(UNPAUSE_TX))),
                childRecordsCheck(
                        PAUSE_TX,
                        CONTRACT_REVERT_EXECUTED,
                        recordWith()
                                .status(TOKEN_HAS_NO_PAUSE_KEY)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(TOKEN_HAS_NO_PAUSE_KEY)))),
                childRecordsCheck(
                        UNPAUSE_TX,
                        CONTRACT_REVERT_EXECUTED,
                        recordWith()
                                .status(TOKEN_HAS_NO_PAUSE_KEY)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(TOKEN_HAS_NO_PAUSE_KEY)))));
    }
}
