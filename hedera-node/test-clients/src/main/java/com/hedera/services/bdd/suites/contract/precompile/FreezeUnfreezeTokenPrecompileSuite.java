// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.precompile;

import static com.google.protobuf.ByteString.copyFromUtf8;
import static com.hedera.node.app.hapi.utils.EthSigsUtils.recoverAddressFromPubKey;
import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.accountWith;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.isLiteralResult;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.keys.KeyShape.CONTRACT;
import static com.hedera.services.bdd.spec.keys.KeyShape.ED25519;
import static com.hedera.services.bdd.spec.keys.KeyShape.sigs;
import static com.hedera.services.bdd.spec.keys.SigControl.ON;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.contractCallLocal;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountDetails;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAliasedAccountInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenFreeze;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUnfreeze;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil.asHeadlongAddress;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromAccountToAlias;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertionsHold;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.childRecordsCheck;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SOURCE_KEY;
import static com.hedera.services.bdd.suites.HapiSuite.TOKEN_TREASURY;
import static com.hedera.services.bdd.suites.contract.Utils.asAddress;
import static com.hedera.services.bdd.suites.contract.Utils.asToken;
import static com.hedera.services.bdd.suites.regression.factories.IdFuzzingProviderFactory.FUNGIBLE_TOKEN;
import static com.hedera.services.bdd.suites.regression.factories.IdFuzzingProviderFactory.NON_FUNGIBLE_TOKEN;
import static com.hedera.services.bdd.suites.token.TokenAssociationSpecs.KNOWABLE_TOKEN;
import static com.hedera.services.bdd.suites.token.TokenAssociationSpecs.VANILLA_TOKEN;
import static com.hedera.services.bdd.suites.utils.contracts.precompile.HTSPrecompileResult.htsPrecompileResult;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_HAS_NO_FREEZE_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_NOT_ASSOCIATED_TO_ACCOUNT;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

import com.google.protobuf.ByteString;
import com.hedera.node.app.hapi.utils.contracts.ParsingConstants;
import com.hedera.node.app.hapi.utils.contracts.ParsingConstants.FunctionType;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.queries.crypto.ExpectedTokenRel;
import com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.TokenFreezeStatus;
import com.hederahashgraph.api.proto.java.TokenID;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
public class FreezeUnfreezeTokenPrecompileSuite {
    public static final String FREEZE_CONTRACT = "FreezeUnfreezeContract";
    private static final String IS_FROZEN_FUNC = "isTokenFrozen";
    public static final String TOKEN_FREEZE_FUNC = "tokenFreeze";
    public static final String TOKEN_UNFREEZE_FUNC = "tokenUnfreeze";
    private static final String ACCOUNT = "anybody";
    private static final String FREEZE_KEY = "freezeKey";
    private static final String MULTI_KEY = "purpose";
    private static final String TREASURY = "treasury";
    private static final long GAS_TO_OFFER = 4_000_000L;
    private static final String INVALID_ADDRESS = "0x0000000000000000000000000000000000123456";
    public static final String PARTY = "party";
    public static final String TOKEN = "token";
    private static final String ACCOUNT_WITHOUT_KEY = "accountWithoutKey";
    private static final String TOKEN_WITHOUT_KEY = "TOKEN_WITHOUT_KEY";
    private static final KeyShape THRESHOLD_KEY_SHAPE = KeyShape.threshOf(1, ED25519, CONTRACT);
    private static final String THRESHOLD_KEY = "THRESHOLD_KEY";
    private static final String ADMIN_KEY = "ADMIN_KEY";

    @HapiTest
    final Stream<DynamicTest> noTokenIdReverts() {
        final AtomicReference<TokenID> vanillaTokenID = new AtomicReference<>();
        final AtomicReference<AccountID> accountID = new AtomicReference<>();
        return hapiTest(
                newKeyNamed(FREEZE_KEY),
                newKeyNamed(MULTI_KEY),
                cryptoCreate(ACCOUNT).balance(100 * ONE_HBAR).exposingCreatedIdTo(accountID::set),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(VANILLA_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .treasury(TOKEN_TREASURY)
                        .freezeKey(FREEZE_KEY)
                        .initialSupply(1_000)
                        .exposingCreatedIdTo(id -> vanillaTokenID.set(asToken(id))),
                uploadInitCode(FREEZE_CONTRACT),
                contractCreate(FREEZE_CONTRACT),
                tokenAssociate(ACCOUNT, VANILLA_TOKEN),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        FREEZE_CONTRACT,
                                        TOKEN_UNFREEZE_FUNC,
                                        asHeadlongAddress(INVALID_ADDRESS),
                                        HapiParserUtil.asHeadlongAddress(asAddress(accountID.get())))
                                .payingWith(ACCOUNT)
                                .gas(GAS_TO_OFFER)
                                .via("UnfreezeTx")
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                        cryptoUpdate(ACCOUNT).key(FREEZE_KEY),
                        contractCall(
                                        FREEZE_CONTRACT,
                                        TOKEN_FREEZE_FUNC,
                                        asHeadlongAddress(INVALID_ADDRESS),
                                        HapiParserUtil.asHeadlongAddress(asAddress(accountID.get())))
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                .payingWith(ACCOUNT)
                                .gas(GAS_TO_OFFER)
                                .via("FreezeTx"))),
                childRecordsCheck(
                        "UnfreezeTx", CONTRACT_REVERT_EXECUTED, recordWith().status(INVALID_TOKEN_ID)),
                childRecordsCheck(
                        "FreezeTx", CONTRACT_REVERT_EXECUTED, recordWith().status(INVALID_TOKEN_ID)));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> isFrozenHappyPathWithAliasLocalCall() {
        final AtomicReference<TokenID> vanillaTokenID = new AtomicReference<>();
        final AtomicReference<String> autoCreatedAccountId = new AtomicReference<>();
        final String accountAlias = "accountAlias";

        return hapiTest(
                newKeyNamed(FREEZE_KEY),
                newKeyNamed(accountAlias).shape(SECP_256K1_SHAPE),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, accountAlias, ONE_HUNDRED_HBARS))
                        .via("autoAccount"),
                getAliasedAccountInfo(accountAlias).exposingContractAccountIdTo(autoCreatedAccountId::set),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(VANILLA_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .treasury(TOKEN_TREASURY)
                        .freezeKey(FREEZE_KEY)
                        .initialSupply(1_000)
                        .exposingCreatedIdTo(id -> vanillaTokenID.set(asToken(id))),
                uploadInitCode(FREEZE_CONTRACT),
                contractCreate(FREEZE_CONTRACT),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCallLocal(
                                FREEZE_CONTRACT,
                                IS_FROZEN_FUNC,
                                HapiParserUtil.asHeadlongAddress(asAddress(vanillaTokenID.get())),
                                HapiParserUtil.asHeadlongAddress(autoCreatedAccountId.get())))));
    }

    @HapiTest
    @Tag(MATS)
    public Stream<DynamicTest> createFungibleTokenFreezeKeyFromHollowAccountAlias() {
        return hapiTest(
                // Create an ECDSA key
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(ACCOUNT).balance(ONE_MILLION_HBARS).maxAutomaticTokenAssociations(2),
                uploadInitCode(FREEZE_CONTRACT),
                contractCreate(FREEZE_CONTRACT),
                cryptoCreate(TREASURY).balance(ONE_MILLION_HBARS),
                withOpContext((spec, opLog) -> {
                    final var ecdsaKey = spec.registry()
                            .getKey(SECP_256K1_SOURCE_KEY)
                            .getECDSASecp256K1()
                            .toByteArray();
                    final var evmAddress = ByteString.copyFrom(recoverAddressFromPubKey(ecdsaKey));
                    spec.registry()
                            .saveAccountAlias(
                                    SECP_256K1_SOURCE_KEY,
                                    AccountID.newBuilder().setAlias(evmAddress).build());

                    allRunFor(
                            spec,
                            // Transfer money to the alias --> creates HOLLOW ACCOUNT
                            cryptoTransfer(movingHbar(ONE_HUNDRED_HBARS).distributing(TREASURY, SECP_256K1_SOURCE_KEY)),
                            // Verify that the account is created and is hollow
                            getAliasedAccountInfo(SECP_256K1_SOURCE_KEY)
                                    .has(accountWith().hasEmptyKey()),
                            // Create a token with the ECDSA alias key as FREEZE key
                            tokenCreate(FUNGIBLE_TOKEN)
                                    .tokenType(FUNGIBLE_COMMON)
                                    .freezeKey(SECP_256K1_SOURCE_KEY)
                                    .initialSupply(100L)
                                    .treasury(TREASURY),
                            // Transfer the created token to a completed account and auto associate it
                            cryptoTransfer(moving(1L, FUNGIBLE_TOKEN).between(TREASURY, ACCOUNT)));
                }),
                withOpContext((spec, opLog) -> {
                    allRunFor(
                            spec,
                            // Freeze the token using the ECDSA key
                            tokenFreeze(FUNGIBLE_TOKEN, ACCOUNT)
                                    .signedBy(ACCOUNT, SECP_256K1_SOURCE_KEY)
                                    .payingWith(ACCOUNT),
                            contractCall(
                                            FREEZE_CONTRACT,
                                            IS_FROZEN_FUNC,
                                            asHeadlongAddress(
                                                    asAddress(spec.registry().getTokenID(FUNGIBLE_TOKEN))),
                                            asHeadlongAddress(
                                                    asAddress(spec.registry().getAccountID(ACCOUNT))))
                                    .via("isTokenFrozenTx"),
                            childRecordsCheck(
                                    "isTokenFrozenTx",
                                    SUCCESS,
                                    recordWith()
                                            .status(SUCCESS)
                                            .contractCallResult(resultWith()
                                                    .contractCallResult(htsPrecompileResult()
                                                            .forFunction(FunctionType.HAPI_IS_FROZEN)
                                                            .withIsFrozen(true)
                                                            .withStatus(SUCCESS)))),
                            // Unfreeze the token using the ECDSA key
                            tokenUnfreeze(FUNGIBLE_TOKEN, ACCOUNT)
                                    .signedBy(ACCOUNT, SECP_256K1_SOURCE_KEY)
                                    .payingWith(ACCOUNT),
                            contractCall(
                                            FREEZE_CONTRACT,
                                            IS_FROZEN_FUNC,
                                            asHeadlongAddress(
                                                    asAddress(spec.registry().getTokenID(FUNGIBLE_TOKEN))),
                                            asHeadlongAddress(
                                                    asAddress(spec.registry().getAccountID(ACCOUNT))))
                                    .via("isTokenUnfrozenTx"),
                            childRecordsCheck(
                                    "isTokenUnfrozenTx",
                                    SUCCESS,
                                    recordWith()
                                            .status(SUCCESS)
                                            .contractCallResult(resultWith()
                                                    .contractCallResult(htsPrecompileResult()
                                                            .forFunction(FunctionType.HAPI_IS_FROZEN)
                                                            .withIsFrozen(false)
                                                            .withStatus(SUCCESS)))));
                }));
    }

    @HapiTest
    @Tag(MATS)
    public Stream<DynamicTest> createNFTTokenFreezeKeyFromHollowAccountAlias() {
        return hapiTest(
                // Create an ECDSA key
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(ACCOUNT).balance(ONE_MILLION_HBARS),
                uploadInitCode(FREEZE_CONTRACT),
                contractCreate(FREEZE_CONTRACT),
                cryptoCreate(TREASURY).balance(ONE_MILLION_HBARS),
                withOpContext((spec, opLog) -> {
                    final var ecdsaKey = spec.registry()
                            .getKey(SECP_256K1_SOURCE_KEY)
                            .getECDSASecp256K1()
                            .toByteArray();
                    final var evmAddress = ByteString.copyFrom(recoverAddressFromPubKey(ecdsaKey));
                    spec.registry()
                            .saveAccountAlias(
                                    SECP_256K1_SOURCE_KEY,
                                    AccountID.newBuilder().setAlias(evmAddress).build());

                    allRunFor(
                            spec,
                            // Transfer money to the alias --> creates HOLLOW ACCOUNT
                            cryptoTransfer(movingHbar(ONE_HUNDRED_HBARS).distributing(TREASURY, SECP_256K1_SOURCE_KEY)),
                            // Verify that the account is created and is hollow
                            getAliasedAccountInfo(SECP_256K1_SOURCE_KEY)
                                    .has(accountWith().hasEmptyKey()),
                            // Create a token with the ECDSA alias key as FREEZE key
                            tokenCreate(NON_FUNGIBLE_TOKEN)
                                    .tokenType(NON_FUNGIBLE_UNIQUE)
                                    .freezeKey(SECP_256K1_SOURCE_KEY)
                                    .supplyKey(SECP_256K1_SOURCE_KEY)
                                    .initialSupply(0L)
                                    .treasury(TREASURY),
                            // Mint the NFT
                            mintToken(NON_FUNGIBLE_TOKEN, List.of(ByteString.copyFromUtf8("metadata1")))
                                    .signedBy(ACCOUNT, SECP_256K1_SOURCE_KEY)
                                    .payingWith(ACCOUNT),
                            // Associate the token to the completed account
                            tokenAssociate(ACCOUNT, NON_FUNGIBLE_TOKEN));
                }),
                withOpContext((spec, opLog) -> {
                    allRunFor(
                            spec,
                            // Freeze the token using the ECDSA key
                            tokenFreeze(NON_FUNGIBLE_TOKEN, ACCOUNT)
                                    .signedBy(ACCOUNT, SECP_256K1_SOURCE_KEY)
                                    .payingWith(ACCOUNT),
                            contractCall(
                                            FREEZE_CONTRACT,
                                            IS_FROZEN_FUNC,
                                            asHeadlongAddress(
                                                    asAddress(spec.registry().getTokenID(NON_FUNGIBLE_TOKEN))),
                                            asHeadlongAddress(
                                                    asAddress(spec.registry().getAccountID(ACCOUNT))))
                                    .via("isTokenFrozenTx"),
                            childRecordsCheck(
                                    "isTokenFrozenTx",
                                    SUCCESS,
                                    recordWith()
                                            .status(SUCCESS)
                                            .contractCallResult(resultWith()
                                                    .contractCallResult(htsPrecompileResult()
                                                            .forFunction(FunctionType.HAPI_IS_FROZEN)
                                                            .withIsFrozen(true)
                                                            .withStatus(SUCCESS)))),
                            // Unfreeze the token using the ECDSA key
                            tokenUnfreeze(NON_FUNGIBLE_TOKEN, ACCOUNT)
                                    .signedBy(ACCOUNT, SECP_256K1_SOURCE_KEY)
                                    .payingWith(ACCOUNT),
                            contractCall(
                                            FREEZE_CONTRACT,
                                            IS_FROZEN_FUNC,
                                            asHeadlongAddress(
                                                    asAddress(spec.registry().getTokenID(NON_FUNGIBLE_TOKEN))),
                                            asHeadlongAddress(
                                                    asAddress(spec.registry().getAccountID(ACCOUNT))))
                                    .via("isTokenUnfrozenTx"),
                            childRecordsCheck(
                                    "isTokenUnfrozenTx",
                                    SUCCESS,
                                    recordWith()
                                            .status(SUCCESS)
                                            .contractCallResult(resultWith()
                                                    .contractCallResult(htsPrecompileResult()
                                                            .forFunction(FunctionType.HAPI_IS_FROZEN)
                                                            .withIsFrozen(false)
                                                            .withStatus(SUCCESS)))));
                }));
    }

    @HapiTest
    final Stream<DynamicTest> freezeUnfreezeFungibleWithNegativeCases() {
        final AtomicReference<TokenID> withoutKeyID = new AtomicReference<>();
        final AtomicReference<AccountID> accountWithoutKeyID = new AtomicReference<>();
        final AtomicReference<TokenID> vanillaTokenID = new AtomicReference<>();
        final AtomicReference<AccountID> accountID = new AtomicReference<>();
        final var NO_KEY_FREEZE_TXN = "NO_KEY_FREEZE_TXN";
        final var NO_KEY_UNFREEZE_TXN = "NO_KEY_UNFREEZE_TXN";
        final var ACCOUNT_HAS_NO_KEY_TXN = "ACCOUNT_HAS_NO_KEY_TXN";

        return hapiTest(
                uploadInitCode(FREEZE_CONTRACT),
                contractCreate(FREEZE_CONTRACT),
                newKeyNamed(FREEZE_KEY).shape(THRESHOLD_KEY_SHAPE.signedWith(sigs(ON, FREEZE_CONTRACT))),
                newKeyNamed(MULTI_KEY),
                cryptoCreate(ACCOUNT).balance(100 * ONE_HBAR).exposingCreatedIdTo(accountID::set),
                cryptoCreate(ACCOUNT_WITHOUT_KEY).exposingCreatedIdTo(accountWithoutKeyID::set),
                cryptoCreate(TOKEN_TREASURY),
                cryptoCreate(ADMIN_KEY),
                tokenCreate(TOKEN_WITHOUT_KEY).exposingCreatedIdTo(id -> withoutKeyID.set(asToken(id))),
                tokenCreate(VANILLA_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .treasury(TOKEN_TREASURY)
                        .freezeKey(FREEZE_KEY)
                        .adminKey(ADMIN_KEY)
                        .initialSupply(1_000)
                        .exposingCreatedIdTo(id -> vanillaTokenID.set(asToken(id))),
                newKeyNamed(THRESHOLD_KEY).shape(THRESHOLD_KEY_SHAPE.signedWith(sigs(ON, FREEZE_CONTRACT))),
                tokenUpdate(VANILLA_TOKEN).freezeKey(THRESHOLD_KEY).signedByPayerAnd(ADMIN_KEY),
                tokenAssociate(ACCOUNT, VANILLA_TOKEN),
                cryptoTransfer(moving(500, VANILLA_TOKEN).between(TOKEN_TREASURY, ACCOUNT)),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        // Fire a transaction with an account that has no key.
                        contractCall(
                                        FREEZE_CONTRACT,
                                        TOKEN_FREEZE_FUNC,
                                        HapiParserUtil.asHeadlongAddress(asAddress(vanillaTokenID.get())),
                                        HapiParserUtil.asHeadlongAddress(asAddress(accountWithoutKeyID.get())))
                                .logged()
                                .signedBy(ACCOUNT)
                                .payingWith(ACCOUNT)
                                .alsoSigningWithFullPrefix(THRESHOLD_KEY)
                                .via(ACCOUNT_HAS_NO_KEY_TXN)
                                .gas(GAS_TO_OFFER)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                        // Fire a freeze transaction for a token that has no freeze key.
                        contractCall(
                                        FREEZE_CONTRACT,
                                        TOKEN_FREEZE_FUNC,
                                        HapiParserUtil.asHeadlongAddress(asAddress(withoutKeyID.get())),
                                        HapiParserUtil.asHeadlongAddress(asAddress(accountID.get())))
                                .logged()
                                .signedBy(ACCOUNT)
                                .payingWith(ACCOUNT)
                                .alsoSigningWithFullPrefix(THRESHOLD_KEY)
                                .via(NO_KEY_FREEZE_TXN)
                                .gas(GAS_TO_OFFER)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                        // Fire a unfreeze transaction for a token that has no freeze key.
                        contractCall(
                                        FREEZE_CONTRACT,
                                        TOKEN_UNFREEZE_FUNC,
                                        HapiParserUtil.asHeadlongAddress(asAddress(withoutKeyID.get())),
                                        HapiParserUtil.asHeadlongAddress(asAddress(accountID.get())))
                                .signedBy(ACCOUNT)
                                .payingWith(ACCOUNT)
                                .alsoSigningWithFullPrefix(THRESHOLD_KEY)
                                .gas(GAS_TO_OFFER)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                .via(NO_KEY_UNFREEZE_TXN),
                        cryptoUpdate(ACCOUNT).key(FREEZE_KEY),
                        // Fire a normal working freeze transaction ->  Success Expected
                        contractCall(
                                        FREEZE_CONTRACT,
                                        TOKEN_FREEZE_FUNC,
                                        HapiParserUtil.asHeadlongAddress(asAddress(vanillaTokenID.get())),
                                        HapiParserUtil.asHeadlongAddress(asAddress(accountID.get())))
                                .logged()
                                .signedBy(ACCOUNT)
                                .payingWith(ACCOUNT)
                                .alsoSigningWithFullPrefix(THRESHOLD_KEY)
                                .gas(GAS_TO_OFFER),
                        getAccountDetails(ACCOUNT)
                                .hasToken(ExpectedTokenRel.relationshipWith(VANILLA_TOKEN)
                                        .freeze(TokenFreezeStatus.Frozen)),
                        // Fire a normal working unfreeze transaction ->  Success Expected
                        contractCall(
                                        FREEZE_CONTRACT,
                                        TOKEN_UNFREEZE_FUNC,
                                        HapiParserUtil.asHeadlongAddress(asAddress(vanillaTokenID.get())),
                                        HapiParserUtil.asHeadlongAddress(asAddress(accountID.get())))
                                .logged()
                                .signedBy(ACCOUNT)
                                .payingWith(ACCOUNT)
                                .alsoSigningWithFullPrefix(THRESHOLD_KEY)
                                .gas(GAS_TO_OFFER),
                        getAccountDetails(ACCOUNT)
                                .hasToken(ExpectedTokenRel.relationshipWith(VANILLA_TOKEN)
                                        .freeze(TokenFreezeStatus.Unfrozen)))),
                childRecordsCheck(
                        ACCOUNT_HAS_NO_KEY_TXN,
                        CONTRACT_REVERT_EXECUTED,
                        recordWith()
                                .status(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT)))),
                childRecordsCheck(
                        NO_KEY_FREEZE_TXN,
                        CONTRACT_REVERT_EXECUTED,
                        recordWith()
                                .status(TOKEN_HAS_NO_FREEZE_KEY)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(TOKEN_HAS_NO_FREEZE_KEY)))),
                childRecordsCheck(
                        NO_KEY_UNFREEZE_TXN,
                        CONTRACT_REVERT_EXECUTED,
                        recordWith()
                                .status(TOKEN_HAS_NO_FREEZE_KEY)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(TOKEN_HAS_NO_FREEZE_KEY)))));
    }

    @HapiTest
    final Stream<DynamicTest> freezeUnfreezeNFTsWithNegativeCases() {
        final var IS_FROZEN_TXN = "IS_FROZEN_TXN";
        final AtomicReference<TokenID> vanillaTokenID = new AtomicReference<>();
        final AtomicReference<AccountID> accountID = new AtomicReference<>();
        final AtomicReference<AccountID> deleted = new AtomicReference<>();
        return hapiTest(
                newKeyNamed(FREEZE_KEY),
                newKeyNamed(ADMIN_KEY),
                newKeyNamed(MULTI_KEY),
                cryptoCreate("deleted").exposingCreatedIdTo(deleted::set),
                cryptoDelete("deleted"),
                cryptoCreate(ACCOUNT).balance(100 * ONE_HBAR).exposingCreatedIdTo(accountID::set),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(KNOWABLE_TOKEN)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .treasury(TOKEN_TREASURY)
                        .adminKey(ADMIN_KEY)
                        .freezeKey(FREEZE_KEY)
                        .supplyKey(MULTI_KEY)
                        .initialSupply(0)
                        .exposingCreatedIdTo(id -> vanillaTokenID.set(asToken(id))),
                mintToken(KNOWABLE_TOKEN, List.of(copyFromUtf8("First!"))),
                uploadInitCode(FREEZE_CONTRACT),
                contractCreate(FREEZE_CONTRACT),
                tokenAssociate(ACCOUNT, KNOWABLE_TOKEN),
                cryptoTransfer(movingUnique(KNOWABLE_TOKEN, 1L).between(TOKEN_TREASURY, ACCOUNT)),
                newKeyNamed(THRESHOLD_KEY).shape(THRESHOLD_KEY_SHAPE.signedWith(sigs(ON, FREEZE_CONTRACT))),
                tokenUpdate(KNOWABLE_TOKEN).freezeKey(THRESHOLD_KEY).signedByPayerAnd(ADMIN_KEY),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        // Freeze with invalid ACCOUNT_DELETED
                        contractCall(
                                        FREEZE_CONTRACT,
                                        TOKEN_UNFREEZE_FUNC,
                                        HapiParserUtil.asHeadlongAddress(asAddress(vanillaTokenID.get())),
                                        HapiParserUtil.asHeadlongAddress(asAddress(deleted.get())))
                                .signedBy(ACCOUNT)
                                .payingWith(ACCOUNT)
                                .alsoSigningWithFullPrefix(THRESHOLD_KEY)
                                .gas(GAS_TO_OFFER)
                                .via("ACCOUNT_DELETED")
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                        cryptoUpdate(ACCOUNT).key(FREEZE_KEY),
                        // Freeze the NFT
                        contractCall(
                                        FREEZE_CONTRACT,
                                        TOKEN_FREEZE_FUNC,
                                        HapiParserUtil.asHeadlongAddress(asAddress(vanillaTokenID.get())),
                                        HapiParserUtil.asHeadlongAddress(asAddress(accountID.get())))
                                .signedBy(ACCOUNT)
                                .payingWith(ACCOUNT)
                                .alsoSigningWithFullPrefix(THRESHOLD_KEY)
                                .gas(GAS_TO_OFFER),
                        getAccountDetails(ACCOUNT)
                                .hasToken(ExpectedTokenRel.relationshipWith(KNOWABLE_TOKEN)
                                        .freeze(TokenFreezeStatus.Frozen)),
                        // Unfreeze the NFT
                        contractCall(
                                        FREEZE_CONTRACT,
                                        TOKEN_UNFREEZE_FUNC,
                                        HapiParserUtil.asHeadlongAddress(asAddress(vanillaTokenID.get())),
                                        HapiParserUtil.asHeadlongAddress(asAddress(accountID.get())))
                                .signedBy(ACCOUNT)
                                .payingWith(ACCOUNT)
                                .alsoSigningWithFullPrefix(THRESHOLD_KEY)
                                .gas(GAS_TO_OFFER),
                        // Check if the nft is frozen
                        contractCall(
                                        FREEZE_CONTRACT,
                                        IS_FROZEN_FUNC,
                                        HapiParserUtil.asHeadlongAddress(asAddress(vanillaTokenID.get())),
                                        HapiParserUtil.asHeadlongAddress(asAddress(accountID.get())))
                                .logged()
                                .signedBy(ACCOUNT)
                                .payingWith(ACCOUNT)
                                .alsoSigningWithFullPrefix(THRESHOLD_KEY)
                                .via(IS_FROZEN_TXN)
                                .gas(GAS_TO_OFFER))),
                childRecordsCheck(
                        "ACCOUNT_DELETED",
                        CONTRACT_REVERT_EXECUTED,
                        recordWith()
                                .status(ACCOUNT_DELETED)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(ACCOUNT_DELETED)))),
                childRecordsCheck(
                        IS_FROZEN_TXN,
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .forFunction(ParsingConstants.FunctionType.HAPI_IS_FROZEN)
                                                .withStatus(SUCCESS)
                                                .withIsFrozen(false)))));
    }

    @HapiTest
    final Stream<DynamicTest> isFrozenHappyPathWithLocalCall() {
        final AtomicReference<AccountID> accountID = new AtomicReference<>();
        final AtomicReference<TokenID> vanillaTokenID = new AtomicReference<>();
        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                newKeyNamed(FREEZE_KEY),
                newKeyNamed(MULTI_KEY),
                cryptoCreate(ACCOUNT).balance(100 * ONE_HBAR).key(FREEZE_KEY).exposingCreatedIdTo(accountID::set),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(VANILLA_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .treasury(TOKEN_TREASURY)
                        .adminKey(ADMIN_KEY)
                        .freezeKey(FREEZE_KEY)
                        .initialSupply(1_000)
                        .exposingCreatedIdTo(id -> vanillaTokenID.set(asToken(id))),
                uploadInitCode(FREEZE_CONTRACT),
                contractCreate(FREEZE_CONTRACT),
                tokenAssociate(ACCOUNT, VANILLA_TOKEN),
                cryptoTransfer(moving(500, VANILLA_TOKEN).between(TOKEN_TREASURY, ACCOUNT)),
                newKeyNamed(THRESHOLD_KEY).shape(THRESHOLD_KEY_SHAPE.signedWith(sigs(ON, FREEZE_CONTRACT))),
                tokenUpdate(VANILLA_TOKEN).freezeKey(THRESHOLD_KEY).signedByPayerAnd(ADMIN_KEY),
                assertionsHold((spec, ctxLog) -> {
                    // Check initial state. Is the token frozen ?
                    final var isFrozenLocalInitialCall = contractCallLocal(
                                    FREEZE_CONTRACT,
                                    IS_FROZEN_FUNC,
                                    HapiParserUtil.asHeadlongAddress(asAddress(vanillaTokenID.get())),
                                    HapiParserUtil.asHeadlongAddress(asAddress(accountID.get())))
                            .has(resultWith()
                                    .resultViaFunctionName(
                                            IS_FROZEN_FUNC, FREEZE_CONTRACT, isLiteralResult(new Object[] {Boolean.FALSE
                                            })));
                    // Freeze the token
                    final var freezeCall = contractCall(
                                    FREEZE_CONTRACT,
                                    TOKEN_FREEZE_FUNC,
                                    HapiParserUtil.asHeadlongAddress(asAddress(vanillaTokenID.get())),
                                    HapiParserUtil.asHeadlongAddress(asAddress(accountID.get())))
                            .logged()
                            .signedBy(ACCOUNT)
                            .payingWith(ACCOUNT)
                            .alsoSigningWithFullPrefix(THRESHOLD_KEY)
                            .gas(GAS_TO_OFFER);
                    // Check final state. Is the token frozen ?
                    final var isFrozenLocalCall = contractCallLocal(
                                    FREEZE_CONTRACT,
                                    IS_FROZEN_FUNC,
                                    HapiParserUtil.asHeadlongAddress(asAddress(vanillaTokenID.get())),
                                    HapiParserUtil.asHeadlongAddress(asAddress(accountID.get())))
                            .has(resultWith()
                                    .resultViaFunctionName(
                                            IS_FROZEN_FUNC, FREEZE_CONTRACT, isLiteralResult(new Object[] {Boolean.TRUE
                                            })));
                    allRunFor(spec, isFrozenLocalInitialCall, freezeCall, isFrozenLocalCall);
                }));
    }
}
