// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.precompile;

import static com.google.protobuf.ByteString.copyFromUtf8;
import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.infrastructure.providers.ops.crypto.RandomAccount.INITIAL_BALANCE;
import static com.hedera.services.bdd.spec.keys.KeyShape.CONTRACT;
import static com.hedera.services.bdd.spec.keys.KeyShape.ED25519;
import static com.hedera.services.bdd.spec.keys.KeyShape.sigs;
import static com.hedera.services.bdd.spec.keys.SigControl.ON;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.childRecordsCheck;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.TOKEN_TREASURY;
import static com.hedera.services.bdd.suites.contract.Utils.asAddress;
import static com.hedera.services.bdd.suites.contract.Utils.asToken;
import static com.hedera.services.bdd.suites.token.TokenAssociationSpecs.VANILLA_TOKEN;
import static com.hedera.services.bdd.suites.utils.contracts.precompile.HTSPrecompileResult.htsPrecompileResult;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_DOES_NOT_OWN_WIPED_NFT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_NFT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_WIPING_AMOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.TokenID;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
public class WipeTokenAccountPrecompileSuite {
    public static final String WIPE_CONTRACT = "WipeTokenAccount";
    public static final String ADMIN_ACCOUNT = "admin";
    private static final String ACCOUNT = "anybody";
    private static final String SECOND_ACCOUNT = "anybodySecond";
    public static final String WIPE_KEY = "wipeKey";
    private static final String MULTI_KEY = "purpose";
    public static final int GAS_TO_OFFER = 1_000_000;
    public static final String WIPE_FUNGIBLE_TOKEN = "wipeFungibleToken";
    public static final String WIPE_NON_FUNGIBLE_TOKEN = "wipeNonFungibleToken";

    public static final String THRESHOLD_KEY = "ThreshKey";
    private static final KeyShape THRESHOLD_KEY_SHAPE = KeyShape.threshOf(1, ED25519, CONTRACT);

    @HapiTest
    final Stream<DynamicTest> wipeFungibleTokenScenarios() {
        final AtomicReference<AccountID> adminAccountID = new AtomicReference<>();
        final AtomicReference<AccountID> accountID = new AtomicReference<>();
        final AtomicReference<AccountID> secondAccountID = new AtomicReference<>();
        final AtomicReference<TokenID> vanillaTokenID = new AtomicReference<>();

        return hapiTest(
                newKeyNamed(WIPE_KEY),
                cryptoCreate(ADMIN_ACCOUNT).exposingCreatedIdTo(adminAccountID::set),
                cryptoCreate(ACCOUNT).exposingCreatedIdTo(accountID::set),
                cryptoCreate(SECOND_ACCOUNT).exposingCreatedIdTo(secondAccountID::set),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(VANILLA_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .treasury(TOKEN_TREASURY)
                        .wipeKey(WIPE_KEY)
                        .adminKey(WIPE_KEY)
                        .initialSupply(1_000)
                        .exposingCreatedIdTo(id -> vanillaTokenID.set(asToken(id))),
                uploadInitCode(WIPE_CONTRACT),
                contractCreate(WIPE_CONTRACT),
                tokenAssociate(ACCOUNT, VANILLA_TOKEN),
                tokenAssociate(SECOND_ACCOUNT, VANILLA_TOKEN),
                cryptoTransfer(moving(500, VANILLA_TOKEN).between(TOKEN_TREASURY, ACCOUNT)),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        WIPE_CONTRACT,
                                        WIPE_FUNGIBLE_TOKEN,
                                        HapiParserUtil.asHeadlongAddress(asAddress(vanillaTokenID.get())),
                                        HapiParserUtil.asHeadlongAddress(asAddress(accountID.get())),
                                        10L)
                                .signedBy(GENESIS, ADMIN_ACCOUNT)
                                .via("accountDoesNotOwnWipeKeyTxn")
                                .gas(GAS_TO_OFFER)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                        newKeyNamed(THRESHOLD_KEY).shape(THRESHOLD_KEY_SHAPE.signedWith(sigs(ON, WIPE_CONTRACT))),
                        tokenUpdate(VANILLA_TOKEN).wipeKey(THRESHOLD_KEY).signedByPayerAnd(WIPE_KEY),
                        cryptoUpdate(ADMIN_ACCOUNT).key(THRESHOLD_KEY),
                        contractCall(
                                        WIPE_CONTRACT,
                                        WIPE_FUNGIBLE_TOKEN,
                                        HapiParserUtil.asHeadlongAddress(asAddress(vanillaTokenID.get())),
                                        HapiParserUtil.asHeadlongAddress(asAddress(accountID.get())),
                                        1_000L)
                                .signedBy(GENESIS, ADMIN_ACCOUNT)
                                .alsoSigningWithFullPrefix(ADMIN_ACCOUNT)
                                .via("amountLargerThanBalanceTxn")
                                .gas(GAS_TO_OFFER)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                        contractCall(
                                        WIPE_CONTRACT,
                                        WIPE_FUNGIBLE_TOKEN,
                                        HapiParserUtil.asHeadlongAddress(asAddress(vanillaTokenID.get())),
                                        HapiParserUtil.asHeadlongAddress(asAddress(secondAccountID.get())),
                                        10L)
                                .signedBy(GENESIS, ADMIN_ACCOUNT)
                                .alsoSigningWithFullPrefix(ADMIN_ACCOUNT)
                                .via("accountDoesNotOwnTokensTxn")
                                .gas(GAS_TO_OFFER)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                        contractCall(
                                        WIPE_CONTRACT,
                                        WIPE_FUNGIBLE_TOKEN,
                                        HapiParserUtil.asHeadlongAddress(asAddress(vanillaTokenID.get())),
                                        HapiParserUtil.asHeadlongAddress(asAddress(accountID.get())),
                                        10L)
                                .alsoSigningWithFullPrefix(ADMIN_ACCOUNT)
                                .via("wipeFungibleTxn")
                                .gas(GAS_TO_OFFER),
                        contractCall(
                                        WIPE_CONTRACT,
                                        WIPE_FUNGIBLE_TOKEN,
                                        HapiParserUtil.asHeadlongAddress(asAddress(vanillaTokenID.get())),
                                        HapiParserUtil.asHeadlongAddress(asAddress(accountID.get())),
                                        0L)
                                .signedBy(GENESIS, ADMIN_ACCOUNT)
                                .alsoSigningWithFullPrefix(ADMIN_ACCOUNT)
                                .via("wipeFungibleTxnWithZeroAmount")
                                .gas(GAS_TO_OFFER))),
                childRecordsCheck(
                        "accountDoesNotOwnWipeKeyTxn",
                        CONTRACT_REVERT_EXECUTED,
                        recordWith()
                                .status(INVALID_SIGNATURE)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(INVALID_SIGNATURE)))),
                childRecordsCheck(
                        "amountLargerThanBalanceTxn",
                        CONTRACT_REVERT_EXECUTED,
                        recordWith()
                                .status(INVALID_WIPING_AMOUNT)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(INVALID_WIPING_AMOUNT)))),
                childRecordsCheck(
                        "accountDoesNotOwnTokensTxn",
                        CONTRACT_REVERT_EXECUTED,
                        recordWith()
                                .status(INVALID_WIPING_AMOUNT)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(INVALID_WIPING_AMOUNT)))),
                childRecordsCheck(
                        "wipeFungibleTxnWithZeroAmount",
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(SUCCESS))
                                        .gasUsed(15284L))),
                getTokenInfo(VANILLA_TOKEN).hasTotalSupply(990),
                getAccountBalance(ACCOUNT).hasTokenBalance(VANILLA_TOKEN, 490));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> wipeNonFungibleTokenScenarios() {
        final AtomicReference<AccountID> adminAccountID = new AtomicReference<>();
        final AtomicReference<AccountID> accountID = new AtomicReference<>();
        final AtomicReference<TokenID> vanillaTokenID = new AtomicReference<>();

        return hapiTest(
                newKeyNamed(WIPE_KEY),
                newKeyNamed(MULTI_KEY),
                cryptoCreate(ADMIN_ACCOUNT).exposingCreatedIdTo(adminAccountID::set),
                cryptoCreate(ACCOUNT).balance(INITIAL_BALANCE).exposingCreatedIdTo(accountID::set),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(VANILLA_TOKEN)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .treasury(TOKEN_TREASURY)
                        .wipeKey(WIPE_KEY)
                        .supplyKey(MULTI_KEY)
                        .adminKey(WIPE_KEY)
                        .initialSupply(0)
                        .exposingCreatedIdTo(id -> vanillaTokenID.set(asToken(id))),
                mintToken(VANILLA_TOKEN, List.of(copyFromUtf8("First!"))),
                mintToken(VANILLA_TOKEN, List.of(copyFromUtf8("Second!"))),
                uploadInitCode(WIPE_CONTRACT),
                contractCreate(WIPE_CONTRACT),
                tokenAssociate(ACCOUNT, VANILLA_TOKEN),
                cryptoTransfer(movingUnique(VANILLA_TOKEN, 1L).between(TOKEN_TREASURY, ACCOUNT)),
                withOpContext((spec, opLog) -> {
                    final var serialNumbers = new long[] {1L};
                    allRunFor(
                            spec,
                            contractCall(
                                            WIPE_CONTRACT,
                                            WIPE_NON_FUNGIBLE_TOKEN,
                                            HapiParserUtil.asHeadlongAddress(asAddress(vanillaTokenID.get())),
                                            HapiParserUtil.asHeadlongAddress(asAddress(accountID.get())),
                                            serialNumbers)
                                    .signedBy(GENESIS, ADMIN_ACCOUNT)
                                    .alsoSigningWithFullPrefix(ADMIN_ACCOUNT)
                                    .via("wipeNonFungibleAccountDoesNotOwnWipeKeyTxn")
                                    .gas(GAS_TO_OFFER)
                                    .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                            newKeyNamed(THRESHOLD_KEY).shape(THRESHOLD_KEY_SHAPE.signedWith(sigs(ON, WIPE_CONTRACT))),
                            tokenUpdate(VANILLA_TOKEN).wipeKey(THRESHOLD_KEY).signedByPayerAnd(WIPE_KEY),
                            cryptoUpdate(ADMIN_ACCOUNT).key(THRESHOLD_KEY),
                            contractCall(
                                            WIPE_CONTRACT,
                                            WIPE_NON_FUNGIBLE_TOKEN,
                                            HapiParserUtil.asHeadlongAddress(asAddress(vanillaTokenID.get())),
                                            HapiParserUtil.asHeadlongAddress(asAddress(accountID.get())),
                                            new long[] {2L})
                                    .signedBy(GENESIS, ADMIN_ACCOUNT)
                                    .alsoSigningWithFullPrefix(ADMIN_ACCOUNT)
                                    .via("wipeNonFungibleAccountDoesNotOwnTheSerialTxn")
                                    .gas(GAS_TO_OFFER)
                                    .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                            contractCall(
                                            WIPE_CONTRACT,
                                            WIPE_NON_FUNGIBLE_TOKEN,
                                            HapiParserUtil.asHeadlongAddress(asAddress(vanillaTokenID.get())),
                                            HapiParserUtil.asHeadlongAddress(asAddress(accountID.get())),
                                            new long[] {-2L})
                                    .signedBy(GENESIS, ADMIN_ACCOUNT)
                                    .alsoSigningWithFullPrefix(ADMIN_ACCOUNT)
                                    .via("wipeNonFungibleNegativeSerialTxn")
                                    .gas(GAS_TO_OFFER)
                                    .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                            contractCall(
                                            WIPE_CONTRACT,
                                            WIPE_NON_FUNGIBLE_TOKEN,
                                            HapiParserUtil.asHeadlongAddress(asAddress(vanillaTokenID.get())),
                                            HapiParserUtil.asHeadlongAddress(asAddress(accountID.get())),
                                            new long[] {3L})
                                    .signedBy(GENESIS, ADMIN_ACCOUNT)
                                    .alsoSigningWithFullPrefix(ADMIN_ACCOUNT)
                                    .via("wipeNonFungibleSerialDoesNotExistsTxn")
                                    .gas(GAS_TO_OFFER)
                                    .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                            contractCall(
                                            WIPE_CONTRACT,
                                            WIPE_NON_FUNGIBLE_TOKEN,
                                            HapiParserUtil.asHeadlongAddress(asAddress(vanillaTokenID.get())),
                                            HapiParserUtil.asHeadlongAddress(asAddress(accountID.get())),
                                            serialNumbers)
                                    .signedBy(GENESIS, ADMIN_ACCOUNT)
                                    .alsoSigningWithFullPrefix(ADMIN_ACCOUNT)
                                    .via("wipeNonFungibleTxn")
                                    .gas(GAS_TO_OFFER));
                }),
                childRecordsCheck(
                        "wipeNonFungibleAccountDoesNotOwnWipeKeyTxn",
                        CONTRACT_REVERT_EXECUTED,
                        recordWith()
                                .status(INVALID_SIGNATURE)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(INVALID_SIGNATURE)))),
                childRecordsCheck(
                        "wipeNonFungibleAccountDoesNotOwnTheSerialTxn",
                        CONTRACT_REVERT_EXECUTED,
                        recordWith()
                                .status(ACCOUNT_DOES_NOT_OWN_WIPED_NFT)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(ACCOUNT_DOES_NOT_OWN_WIPED_NFT)))),
                childRecordsCheck(
                        "wipeNonFungibleNegativeSerialTxn",
                        CONTRACT_REVERT_EXECUTED,
                        recordWith()
                                .status(INVALID_NFT_ID)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(INVALID_NFT_ID)))),
                childRecordsCheck(
                        "wipeNonFungibleSerialDoesNotExistsTxn",
                        CONTRACT_REVERT_EXECUTED,
                        recordWith()
                                .status(INVALID_NFT_ID)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(INVALID_NFT_ID)))),
                childRecordsCheck(
                        "wipeNonFungibleTxn",
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(SUCCESS))
                                        .gasUsed(15284L))),
                getTokenInfo(VANILLA_TOKEN).hasTotalSupply(1),
                getAccountBalance(ACCOUNT).hasTokenBalance(VANILLA_TOKEN, 0));
    }
}
