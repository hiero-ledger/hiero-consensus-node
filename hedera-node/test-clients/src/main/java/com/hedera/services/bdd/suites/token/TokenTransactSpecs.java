// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.token;

import static com.google.protobuf.ByteString.copyFromUtf8;
import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.TOKEN;
import static com.hedera.services.bdd.spec.HapiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.accountWith;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.changeFromSnapshot;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.tokenChangeFromSnapshot;
import static com.hedera.services.bdd.spec.assertions.AutoAssocAsserts.accountTokenPairs;
import static com.hedera.services.bdd.spec.assertions.NoFungibleTransfers.changingNoFungibleBalances;
import static com.hedera.services.bdd.spec.assertions.NoNftTransfers.changingNoNftOwners;
import static com.hedera.services.bdd.spec.assertions.SomeFungibleTransfers.changingFungibleBalances;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenNftInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.queries.crypto.ExpectedTokenRel.relationshipWith;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createDefaultContract;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenDissociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenFreeze;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUpdate;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHbarFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHbarFeeInheritingRoyaltyCollector;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHtsFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHtsFeeInheritingRoyaltyCollector;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fractionalFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fractionalFeeNetOfTransfers;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.royaltyFeeNoFallback;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.royaltyFeeWithFallback;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.balanceSnapshot;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.blockingOrder;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.inParallel;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.tokenBalanceSnapshot;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withTargetLedgerId;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.FEE_COLLECTOR;
import static com.hedera.services.bdd.suites.HapiSuite.FUNDING;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.HBAR_TOKEN_SENTINEL;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.TOKEN_TREASURY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_AMOUNT_TRANSFERS_ONLY_ALLOWED_FOR_FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_FROZEN_FOR_TOKEN;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_KYC_NOT_GRANTED_FOR_TOKEN;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_REPEATED_IN_ACCOUNT_AMOUNTS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_ACCOUNT_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_SENDER_ACCOUNT_BALANCE_FOR_CUSTOM_FEE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TOKEN_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_AMOUNTS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_NFT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.NFT_TRANSFERS_ONLY_ALLOWED_FOR_NON_FUNGIBLE_UNIQUE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.NO_REMAINING_AUTOMATIC_ASSOCIATIONS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SENDER_DOES_NOT_OWN_NFT_SERIAL_NO;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_NOT_ASSOCIATED_TO_ACCOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_WAS_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSFERS_NOT_ZERO_SUM_FOR_TOKEN;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hederahashgraph.api.proto.java.CustomFee;
import com.hederahashgraph.api.proto.java.NftTransfer;
import com.hederahashgraph.api.proto.java.TokenTransferList;
import com.hederahashgraph.api.proto.java.TokenType;
import java.util.List;
import java.util.OptionalLong;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(TOKEN)
@Tag(MATS)
public class TokenTransactSpecs {
    public static final String PAYER = "payer";
    private static final long TOTAL_SUPPLY = 1_000;
    private static final String A_TOKEN = "TokenA";
    private static final String B_TOKEN = "TokenB";
    private static final String FIRST_USER = "Client1";
    private static final String SECOND_USER = "Client2";
    public static final String CIVILIAN = "civilian";
    public static final String NEW_TREASURY = "newTreasury";
    public static final String SIGNING_KEY_FIRST_USER = "signingKeyFirstUser";
    public static final String FIRST_TREASURY = "firstTreasury";
    public static final String BENEFICIARY = "beneficiary";
    public static final String MULTIPURPOSE = "multipurpose";
    public static final String SECOND_TREASURY = "secondTreasury";
    public static final String WEST_WIND_ART = "westWindArt";
    public static final String FREEZE_KEY = "freezeKey";
    public static final String SPONSOR = "sponsor";
    public static final String TXN_FROM_TREASURY = "txnFromTreasury";
    public static final String TOKEN_WITH_FRACTIONAL_FEE = "TokenWithFractionalFee";
    public static final String DEBBIE = "Debbie";
    public static final String COLLECTION = "collection";
    public static final String MULTI_PURPOSE = "multiPurpose";
    public static final String SUPPLY_KEY = "supplyKey";
    public static final String SENTINEL_ACCOUNT = "0.0.0";
    public static final String SIGNING_KEY_TREASURY = "signingKeyTreasury";
    public static final String OLD_TREASURY = "oldTreasury";
    public static final String EDGAR = "Edgar";
    public static final String TOKEN_WITH_NESTED_FEE = "TokenWithNestedFee";
    public static final String NESTED_TOKEN_TREASURY = "NestedTokenTreasury";
    public static final String AMELIE = "amelie";
    public static final String FUGUES_AND_FANTASTICS = "Fugues and fantastics";
    public static final String RANDOM_BENEFICIARY = "randomBeneficiary";
    public static final String SUPPLY = "supply";
    public static final String UNIQUE = "unique";
    public static final String FUNGIBLE = "fungible";
    public static final String TRANSFER_TXN = "transferTxn";

    @HapiTest
    final Stream<DynamicTest> autoAssociationWithFrozenByDefaultTokenHasNoSideEffectsOrHistory() {
        final var beneficiary = BENEFICIARY;
        final var uniqueToken = UNIQUE;
        final var fungibleToken = FUNGIBLE;
        final var otherFungibleToken = "otherFungibleToken";
        final var multiPurpose = MULTI_PURPOSE;
        final var transferTxn = TRANSFER_TXN;

        return hapiTest(
                newKeyNamed(multiPurpose),
                cryptoCreate(TOKEN_TREASURY).maxAutomaticTokenAssociations(1),
                cryptoCreate(beneficiary).maxAutomaticTokenAssociations(2),
                tokenCreate(fungibleToken)
                        .freezeDefault(true)
                        .freezeKey(multiPurpose)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(1_000L)
                        .treasury(beneficiary),
                tokenCreate(otherFungibleToken).tokenType(FUNGIBLE_COMMON).treasury(beneficiary),
                tokenCreate(uniqueToken)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .supplyKey(multiPurpose)
                        .initialSupply(0L)
                        .treasury(TOKEN_TREASURY),
                mintToken(uniqueToken, List.of(copyFromUtf8("ONE"), copyFromUtf8("TWO"))),
                getAccountInfo(beneficiary).savingSnapshot(beneficiary),
                getAccountInfo(TOKEN_TREASURY).savingSnapshot(TOKEN_TREASURY),
                cryptoTransfer(
                                movingUnique(uniqueToken, 1L).between(TOKEN_TREASURY, beneficiary),
                                moving(500, fungibleToken).between(beneficiary, TOKEN_TREASURY))
                        .via(transferTxn)
                        .hasKnownStatus(ACCOUNT_FROZEN_FOR_TOKEN),
                getTxnRecord(transferTxn).hasPriority(recordWith().autoAssociated(accountTokenPairs(List.of()))),
                getAccountInfo(beneficiary)
                        .hasAlreadyUsedAutomaticAssociations(0)
                        .has(accountWith().noChangesFromSnapshot(beneficiary)),
                getAccountInfo(TOKEN_TREASURY)
                        .hasAlreadyUsedAutomaticAssociations(0)
                        .has(accountWith().noChangesFromSnapshot(TOKEN_TREASURY)),
                /* The treasury should still have an open auto-association slots */
                cryptoTransfer(moving(500, otherFungibleToken).between(beneficiary, TOKEN_TREASURY)));
    }

    @HapiTest
    final Stream<DynamicTest> autoAssociationWithKycTokenHasNoSideEffectsOrHistory() {
        final var beneficiary = BENEFICIARY;
        final var uniqueToken = UNIQUE;
        final var fungibleToken = FUNGIBLE;
        final var otherFungibleToken = "otherFungibleToken";
        final var multiPurpose = MULTI_PURPOSE;
        final var transferTxn = TRANSFER_TXN;

        return hapiTest(
                newKeyNamed(multiPurpose),
                cryptoCreate(TOKEN_TREASURY).maxAutomaticTokenAssociations(1),
                cryptoCreate(beneficiary).maxAutomaticTokenAssociations(2),
                tokenCreate(fungibleToken)
                        .kycKey(multiPurpose)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(1_000L)
                        .treasury(beneficiary),
                tokenCreate(otherFungibleToken).tokenType(FUNGIBLE_COMMON).treasury(beneficiary),
                tokenCreate(uniqueToken)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .supplyKey(multiPurpose)
                        .initialSupply(0L)
                        .treasury(TOKEN_TREASURY),
                mintToken(uniqueToken, List.of(copyFromUtf8("ONE"), copyFromUtf8("TWO"))),
                getAccountInfo(beneficiary).savingSnapshot(beneficiary),
                getAccountInfo(TOKEN_TREASURY).savingSnapshot(TOKEN_TREASURY),
                cryptoTransfer(
                                movingUnique(uniqueToken, 1L).between(TOKEN_TREASURY, beneficiary),
                                moving(500, fungibleToken).between(beneficiary, TOKEN_TREASURY))
                        .via(transferTxn)
                        .hasKnownStatus(ACCOUNT_KYC_NOT_GRANTED_FOR_TOKEN),
                getTxnRecord(transferTxn).hasPriority(recordWith().autoAssociated(accountTokenPairs(List.of()))),
                getAccountInfo(beneficiary)
                        .hasAlreadyUsedAutomaticAssociations(0)
                        .has(accountWith().noChangesFromSnapshot(beneficiary)),
                getAccountInfo(TOKEN_TREASURY)
                        .hasAlreadyUsedAutomaticAssociations(0)
                        .has(accountWith().noChangesFromSnapshot(TOKEN_TREASURY)),
                /* The treasury should still have an open auto-association slots */
                cryptoTransfer(moving(500, otherFungibleToken).between(beneficiary, TOKEN_TREASURY)));
    }

    @HapiTest
    final Stream<DynamicTest> failedAutoAssociationHasNoSideEffectsOrHistoryForUnrelatedProblem() {
        final var beneficiary = BENEFICIARY;
        final var unluckyBeneficiary = "unluckyBeneficiary";
        final var thirdParty = "thirdParty";
        final var uniqueToken = UNIQUE;
        final var fungibleToken = FUNGIBLE;
        final var multiPurpose = MULTI_PURPOSE;
        final var transferTxn = TRANSFER_TXN;

        return hapiTest(
                newKeyNamed(multiPurpose),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(fungibleToken)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(1_000L)
                        .treasury(TOKEN_TREASURY),
                tokenCreate(uniqueToken)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .supplyKey(multiPurpose)
                        .initialSupply(0L)
                        .treasury(TOKEN_TREASURY),
                mintToken(uniqueToken, List.of(copyFromUtf8("ONE"), copyFromUtf8("TWO"))),
                cryptoCreate(beneficiary).maxAutomaticTokenAssociations(2),
                cryptoCreate(unluckyBeneficiary),
                cryptoCreate(thirdParty).maxAutomaticTokenAssociations(1),
                tokenAssociate(unluckyBeneficiary, uniqueToken),
                getAccountInfo(beneficiary).savingSnapshot(beneficiary),
                getAccountInfo(unluckyBeneficiary).savingSnapshot(unluckyBeneficiary),
                cryptoTransfer(movingUnique(uniqueToken, 2L).between(TOKEN_TREASURY, thirdParty)),
                cryptoTransfer(
                                movingUnique(uniqueToken, 1L).between(TOKEN_TREASURY, beneficiary),
                                moving(500, fungibleToken).between(TOKEN_TREASURY, beneficiary),
                                movingUnique(uniqueToken, 2L).between(TOKEN_TREASURY, unluckyBeneficiary))
                        .via(transferTxn)
                        .hasKnownStatus(SENDER_DOES_NOT_OWN_NFT_SERIAL_NO),
                getTxnRecord(transferTxn).hasPriority(recordWith().autoAssociated(accountTokenPairs(List.of()))),
                getAccountInfo(beneficiary)
                        .hasAlreadyUsedAutomaticAssociations(0)
                        .has(accountWith().noChangesFromSnapshot(beneficiary)),
                /* The beneficiary should still have two open auto-association slots */
                cryptoTransfer(
                        movingUnique(uniqueToken, 1L).between(TOKEN_TREASURY, beneficiary),
                        moving(500, fungibleToken).between(TOKEN_TREASURY, beneficiary)));
    }

    @HapiTest
    final Stream<DynamicTest> newSlotsCanBeOpenedViaUpdate() {
        final var beneficiary = BENEFICIARY;
        final var uniqueToken = UNIQUE;
        final var firstFungibleToken = "firstFungibleToken";
        final var secondFungibleToken = "secondFungibleToken";
        final var thirdFungibleToken = "thirdFungibleToken";
        final var multiPurpose = MULTI_PURPOSE;
        final var firstXfer = "firstXfer";
        final var secondXfer = "secondXfer";

        return defaultHapiSpec("NewSlotsCanBeOpenedViaUpdate")
                .given(
                        newKeyNamed(multiPurpose),
                        cryptoCreate(TOKEN_TREASURY),
                        tokenCreate(firstFungibleToken)
                                .tokenType(TokenType.FUNGIBLE_COMMON)
                                .initialSupply(1_000L)
                                .treasury(TOKEN_TREASURY),
                        tokenCreate(secondFungibleToken)
                                .tokenType(TokenType.FUNGIBLE_COMMON)
                                .initialSupply(1_000L)
                                .treasury(TOKEN_TREASURY),
                        tokenCreate(thirdFungibleToken)
                                .tokenType(TokenType.FUNGIBLE_COMMON)
                                .initialSupply(1_000L)
                                .treasury(TOKEN_TREASURY),
                        tokenCreate(uniqueToken)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .supplyKey(multiPurpose)
                                .initialSupply(0L)
                                .treasury(TOKEN_TREASURY),
                        mintToken(uniqueToken, List.of(copyFromUtf8("ONE"), copyFromUtf8("TWO"))),
                        cryptoCreate(beneficiary).maxAutomaticTokenAssociations(1),
                        getAccountInfo(beneficiary).savingSnapshot(beneficiary),
                        tokenAssociate(beneficiary, secondFungibleToken))
                .when(
                        cryptoTransfer(movingUnique(uniqueToken, 1L).between(TOKEN_TREASURY, beneficiary))
                                .via(firstXfer),
                        cryptoTransfer(moving(500, firstFungibleToken).between(TOKEN_TREASURY, beneficiary))
                                .hasKnownStatus(NO_REMAINING_AUTOMATIC_ASSOCIATIONS),
                        // Dissociating from a token that didn't use a slot doesn't free one up
                        tokenDissociate(beneficiary, secondFungibleToken),
                        cryptoTransfer(moving(500, firstFungibleToken).between(TOKEN_TREASURY, beneficiary))
                                .hasKnownStatus(NO_REMAINING_AUTOMATIC_ASSOCIATIONS),
                        cryptoUpdate(beneficiary).maxAutomaticAssociations(2),
                        cryptoTransfer(moving(500, firstFungibleToken).between(TOKEN_TREASURY, beneficiary))
                                .via(secondXfer),
                        cryptoTransfer(moving(500, thirdFungibleToken).between(TOKEN_TREASURY, beneficiary))
                                .hasKnownStatus(NO_REMAINING_AUTOMATIC_ASSOCIATIONS),
                        tokenAssociate(beneficiary, thirdFungibleToken),
                        cryptoTransfer(moving(500, thirdFungibleToken).between(TOKEN_TREASURY, beneficiary)))
                .then(
                        getTxnRecord(firstXfer)
                                .hasPriority(recordWith()
                                        .autoAssociated(accountTokenPairs(List.of(Pair.of(beneficiary, uniqueToken))))),
                        getTxnRecord(secondXfer)
                                .hasPriority(recordWith()
                                        .autoAssociated(
                                                accountTokenPairs(List.of(Pair.of(beneficiary, firstFungibleToken))))),
                        getAccountInfo(beneficiary)
                                .hasAlreadyUsedAutomaticAssociations(2)
                                .has(accountWith()
                                        .newAssociationsFromSnapshot(
                                                beneficiary,
                                                List.of(
                                                        relationshipWith(firstFungibleToken)
                                                                .balance(500),
                                                        relationshipWith(uniqueToken)
                                                                .balance(1)))));
    }

    @HapiTest
    final Stream<DynamicTest> newSlotsCanBeOpenedViaDissociate() {
        final var beneficiary = BENEFICIARY;
        final var uniqueToken = UNIQUE;
        final var firstFungibleToken = "firstFungibleToken";
        final var multiPurpose = MULTI_PURPOSE;
        final var firstXfer = "firstXfer";
        final var secondXfer = "secondXfer";

        return defaultHapiSpec("NewSlotsCanBeOpenedViaDissociate")
                .given(
                        newKeyNamed(multiPurpose),
                        cryptoCreate(TOKEN_TREASURY),
                        tokenCreate(firstFungibleToken)
                                .tokenType(TokenType.FUNGIBLE_COMMON)
                                .initialSupply(1_000L)
                                .treasury(TOKEN_TREASURY),
                        tokenCreate(uniqueToken)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .supplyKey(multiPurpose)
                                .initialSupply(0L)
                                .treasury(TOKEN_TREASURY),
                        mintToken(uniqueToken, List.of(copyFromUtf8("ONE"), copyFromUtf8("TWO"))),
                        cryptoCreate(beneficiary).maxAutomaticTokenAssociations(1),
                        getAccountInfo(beneficiary).savingSnapshot(beneficiary))
                .when(
                        cryptoTransfer(movingUnique(uniqueToken, 1L).between(TOKEN_TREASURY, beneficiary))
                                .via(firstXfer),
                        cryptoTransfer(moving(500, firstFungibleToken).between(TOKEN_TREASURY, beneficiary))
                                .hasKnownStatus(NO_REMAINING_AUTOMATIC_ASSOCIATIONS),
                        cryptoTransfer(movingUnique(uniqueToken, 1L).between(beneficiary, TOKEN_TREASURY)),
                        tokenDissociate(beneficiary, uniqueToken),
                        cryptoTransfer(moving(500, firstFungibleToken).between(TOKEN_TREASURY, beneficiary))
                                .via(secondXfer))
                .then(
                        getTxnRecord(firstXfer)
                                .hasPriority(recordWith()
                                        .autoAssociated(accountTokenPairs(List.of(Pair.of(beneficiary, uniqueToken))))),
                        getTxnRecord(secondXfer)
                                .hasPriority(recordWith()
                                        .autoAssociated(
                                                accountTokenPairs(List.of(Pair.of(beneficiary, firstFungibleToken))))),
                        getAccountInfo(beneficiary)
                                .hasAlreadyUsedAutomaticAssociations(1)
                                .has(accountWith()
                                        .newAssociationsFromSnapshot(
                                                beneficiary,
                                                List.of(relationshipWith(firstFungibleToken)
                                                        .balance(500)))));
    }

    @HapiTest
    final Stream<DynamicTest> happyPathAutoAssociationsWorkForBothTokenTypes() {
        final var beneficiary = BENEFICIARY;
        final var uniqueToken = UNIQUE;
        final var fungibleToken = FUNGIBLE;
        final var multiPurpose = MULTI_PURPOSE;
        final var transferTxn = TRANSFER_TXN;

        return defaultHapiSpec("HappyPathAutoAssociationsWorkForBothTokenTypes")
                .given(
                        newKeyNamed(multiPurpose),
                        cryptoCreate(TOKEN_TREASURY),
                        tokenCreate(fungibleToken)
                                .tokenType(TokenType.FUNGIBLE_COMMON)
                                .initialSupply(1_000L)
                                .treasury(TOKEN_TREASURY),
                        tokenCreate(uniqueToken)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .supplyKey(multiPurpose)
                                .initialSupply(0L)
                                .treasury(TOKEN_TREASURY),
                        mintToken(uniqueToken, List.of(copyFromUtf8("ONE"), copyFromUtf8("TWO"))),
                        cryptoCreate(beneficiary).maxAutomaticTokenAssociations(2),
                        getAccountInfo(beneficiary).savingSnapshot(beneficiary))
                .when(cryptoTransfer(
                                movingUnique(uniqueToken, 1L).between(TOKEN_TREASURY, beneficiary),
                                moving(500, fungibleToken).between(TOKEN_TREASURY, beneficiary))
                        .via(transferTxn))
                .then(
                        getTxnRecord(transferTxn)
                                .hasPriority(recordWith()
                                        .autoAssociated(accountTokenPairs(List.of(
                                                Pair.of(beneficiary, fungibleToken),
                                                Pair.of(beneficiary, uniqueToken))))),
                        getAccountInfo(beneficiary)
                                .hasAlreadyUsedAutomaticAssociations(2)
                                .has(accountWith()
                                        .newAssociationsFromSnapshot(
                                                beneficiary,
                                                List.of(
                                                        relationshipWith(fungibleToken)
                                                                .balance(500),
                                                        relationshipWith(uniqueToken)
                                                                .balance(1)))));
    }

    @HapiTest
    final Stream<DynamicTest> transferListsEnforceTokenTypeRestrictions() {
        final var theAccount = "anybody";
        final var nonFungibleToken = "non-fungible";
        final var theKey = MULTIPURPOSE;
        return defaultHapiSpec("TransferListsEnforceTokenTypeRestrictions")
                .given(
                        newKeyNamed(theKey),
                        cryptoCreate(theAccount),
                        cryptoCreate(TOKEN_TREASURY),
                        tokenCreate(A_TOKEN)
                                .tokenType(TokenType.FUNGIBLE_COMMON)
                                .initialSupply(1_000L)
                                .treasury(TOKEN_TREASURY),
                        tokenCreate(nonFungibleToken)
                                .supplyKey(theKey)
                                .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                                .initialSupply(0L)
                                .treasury(TOKEN_TREASURY))
                .when(
                        mintToken(nonFungibleToken, List.of(copyFromUtf8("dark"))),
                        tokenAssociate(theAccount, List.of(A_TOKEN, nonFungibleToken)))
                .then(
                        cryptoTransfer(movingUnique(A_TOKEN, 1).between(TOKEN_TREASURY, theAccount))
                                .hasKnownStatus(INVALID_NFT_ID),
                        cryptoTransfer(moving(1, nonFungibleToken).between(TOKEN_TREASURY, theAccount))
                                .hasKnownStatus(ACCOUNT_AMOUNT_TRANSFERS_ONLY_ALLOWED_FOR_FUNGIBLE_COMMON));
    }

    @HapiTest
    final Stream<DynamicTest> recordsIncludeBothFungibleTokenChangesAndOwnershipChange() {
        final var theUniqueToken = "special";
        final var theCommonToken = "quotidian";
        final var theAccount = "lucky";
        final var theKey = MULTIPURPOSE;
        final var theTxn = "diverseXfer";

        return defaultHapiSpec("RecordsIncludeBothFungibleTokenChangesAndOwnershipChange")
                .given(
                        newKeyNamed(theKey),
                        cryptoCreate(theAccount),
                        cryptoCreate(TOKEN_TREASURY),
                        tokenCreate(theCommonToken)
                                .tokenType(TokenType.FUNGIBLE_COMMON)
                                .initialSupply(1_234_567L)
                                .treasury(TOKEN_TREASURY),
                        tokenCreate(theUniqueToken)
                                .supplyKey(theKey)
                                .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                                .initialSupply(0L)
                                .treasury(TOKEN_TREASURY),
                        mintToken(theUniqueToken, List.of(copyFromUtf8("Doesn't matter"))),
                        tokenAssociate(theAccount, theUniqueToken),
                        tokenAssociate(theAccount, theCommonToken))
                .when(cryptoTransfer(
                                moving(1, theCommonToken).between(TOKEN_TREASURY, theAccount),
                                movingUnique(theUniqueToken, 1).between(TOKEN_TREASURY, theAccount))
                        .via(theTxn))
                .then(getTxnRecord(theTxn).logged());
    }

    @HapiTest
    final Stream<DynamicTest> cannotGiveNftsToDissociatedContractsOrAccounts() {
        final var theContract = "tbd";
        final var theAccount = "alsoTbd";
        final var theKey = MULTIPURPOSE;
        return defaultHapiSpec("CannotGiveNftsToDissociatedContractsOrAccounts")
                .given(
                        newKeyNamed(theKey),
                        createDefaultContract(theContract),
                        cryptoCreate(theAccount),
                        cryptoCreate(TOKEN_TREASURY),
                        tokenCreate(A_TOKEN)
                                .supplyKey(theKey)
                                .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                                .initialSupply(0L)
                                .treasury(TOKEN_TREASURY),
                        tokenAssociate(theContract, A_TOKEN),
                        tokenAssociate(theAccount, A_TOKEN),
                        mintToken(A_TOKEN, List.of(copyFromUtf8("dark"), copyFromUtf8("matter"))))
                .when(
                        getContractInfo(theContract).hasToken(relationshipWith(A_TOKEN)),
                        getAccountInfo(theAccount).hasToken(relationshipWith(A_TOKEN)),
                        tokenDissociate(theContract, A_TOKEN),
                        tokenDissociate(theAccount, A_TOKEN),
                        getContractInfo(theContract).hasNoTokenRelationship(A_TOKEN),
                        getAccountInfo(theAccount).hasNoTokenRelationship(A_TOKEN))
                .then(
                        cryptoTransfer(movingUnique(A_TOKEN, 1).between(TOKEN_TREASURY, theContract))
                                .hasKnownStatus(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT),
                        cryptoTransfer(movingUnique(A_TOKEN, 1).between(TOKEN_TREASURY, theAccount))
                                .hasKnownStatus(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT),
                        tokenAssociate(theContract, A_TOKEN),
                        tokenAssociate(theAccount, A_TOKEN),
                        cryptoTransfer(movingUnique(A_TOKEN, 1).between(TOKEN_TREASURY, theContract)),
                        cryptoTransfer(movingUnique(A_TOKEN, 2).between(TOKEN_TREASURY, theAccount)),
                        getAccountBalance(theAccount).hasTokenBalance(A_TOKEN, 1),
                        getAccountBalance(theContract).hasTokenBalance(A_TOKEN, 1));
    }

    @HapiTest
    final Stream<DynamicTest> cannotSendFungibleToDissociatedContractsOrAccounts() {
        final var theContract = "tbd";
        final var theAccount = "alsoTbd";
        return defaultHapiSpec("CannotSendFungibleToDissociatedContractsOrAccounts")
                .given(
                        createDefaultContract(theContract),
                        cryptoCreate(theAccount),
                        cryptoCreate(TOKEN_TREASURY),
                        tokenCreate(A_TOKEN)
                                .tokenType(TokenType.FUNGIBLE_COMMON)
                                .initialSupply(1_234_567L)
                                .treasury(TOKEN_TREASURY),
                        tokenAssociate(theContract, A_TOKEN),
                        tokenAssociate(theAccount, A_TOKEN))
                .when(
                        getContractInfo(theContract).hasToken(relationshipWith(A_TOKEN)),
                        getAccountInfo(theAccount).hasToken(relationshipWith(A_TOKEN)),
                        tokenDissociate(theContract, A_TOKEN),
                        tokenDissociate(theAccount, A_TOKEN),
                        getContractInfo(theContract).hasNoTokenRelationship(A_TOKEN),
                        getAccountInfo(theAccount).hasNoTokenRelationship(A_TOKEN))
                .then(
                        cryptoTransfer(moving(1, A_TOKEN).between(TOKEN_TREASURY, theContract))
                                .hasKnownStatus(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT),
                        cryptoTransfer(moving(1, A_TOKEN).between(TOKEN_TREASURY, theAccount))
                                .hasKnownStatus(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT),
                        tokenAssociate(theContract, A_TOKEN),
                        tokenAssociate(theAccount, A_TOKEN),
                        cryptoTransfer(moving(1, A_TOKEN).between(TOKEN_TREASURY, theContract)),
                        cryptoTransfer(moving(1, A_TOKEN).between(TOKEN_TREASURY, theAccount)),
                        getAccountBalance(theAccount).hasTokenBalance(A_TOKEN, 1L),
                        getAccountBalance(theContract).hasTokenBalance(A_TOKEN, 1L));
    }

    @HapiTest
    final Stream<DynamicTest> missingEntitiesRejected() {
        return defaultHapiSpec("missingEntitiesRejected")
                .given(tokenCreate("some").treasury(DEFAULT_PAYER))
                .when()
                .then(
                        cryptoTransfer(moving(1L, "some").between(DEFAULT_PAYER, SENTINEL_ACCOUNT))
                                .signedBy(DEFAULT_PAYER)
                                .hasKnownStatus(INVALID_ACCOUNT_ID),
                        cryptoTransfer(moving(100_000_000_000_000L, SENTINEL_ACCOUNT)
                                        .between(DEFAULT_PAYER, FUNDING))
                                .signedBy(DEFAULT_PAYER)
                                .hasPrecheck(INVALID_TOKEN_ID));
    }

    @HapiTest
    final Stream<DynamicTest> balancesAreChecked() {
        return defaultHapiSpec("BalancesAreChecked")
                .given(
                        cryptoCreate(PAYER),
                        cryptoCreate(FIRST_TREASURY),
                        cryptoCreate(SECOND_TREASURY),
                        cryptoCreate(BENEFICIARY))
                .when(
                        tokenCreate(A_TOKEN).initialSupply(100).treasury(FIRST_TREASURY),
                        tokenAssociate(BENEFICIARY, A_TOKEN))
                .then(
                        cryptoTransfer(moving(100_000_000_000_000L, A_TOKEN).between(FIRST_TREASURY, BENEFICIARY))
                                .payingWith(PAYER)
                                .signedBy(PAYER, FIRST_TREASURY)
                                .fee(ONE_HUNDRED_HBARS)
                                .hasKnownStatus(INSUFFICIENT_TOKEN_BALANCE),
                        cryptoTransfer(
                                        moving(1, A_TOKEN).between(FIRST_TREASURY, BENEFICIARY),
                                        movingHbar(ONE_HUNDRED_HBARS).between(FIRST_TREASURY, BENEFICIARY))
                                .payingWith(PAYER)
                                .signedBy(PAYER, FIRST_TREASURY)
                                .fee(ONE_HUNDRED_HBARS)
                                .hasKnownStatus(INSUFFICIENT_ACCOUNT_BALANCE));
    }

    @HapiTest
    final Stream<DynamicTest> accountsMustBeExplicitlyUnfrozenOnlyIfDefaultFreezeIsTrue() {
        return defaultHapiSpec("AccountsMustBeExplicitlyUnfrozenOnlyIfDefaultFreezeIsTrue")
                .given(
                        cryptoCreate(RANDOM_BENEFICIARY).balance(0L),
                        cryptoCreate(TOKEN_TREASURY).balance(0L),
                        cryptoCreate(PAYER),
                        newKeyNamed(FREEZE_KEY))
                .when(
                        tokenCreate(A_TOKEN)
                                .treasury(TOKEN_TREASURY)
                                .freezeKey(FREEZE_KEY)
                                .freezeDefault(true),
                        tokenAssociate(RANDOM_BENEFICIARY, A_TOKEN),
                        cryptoTransfer(moving(100, A_TOKEN).between(TOKEN_TREASURY, RANDOM_BENEFICIARY))
                                .hasKnownStatus(ACCOUNT_FROZEN_FOR_TOKEN),
                        /* and */
                        tokenCreate(B_TOKEN).treasury(TOKEN_TREASURY).freezeDefault(false),
                        tokenAssociate(RANDOM_BENEFICIARY, B_TOKEN),
                        cryptoTransfer(moving(100, B_TOKEN).between(TOKEN_TREASURY, RANDOM_BENEFICIARY))
                                .payingWith(PAYER)
                                .via("successfulTransfer"))
                .then(
                        getAccountBalance(RANDOM_BENEFICIARY).hasTokenBalance(B_TOKEN, 100),
                        getTxnRecord("successfulTransfer").logged());
    }

    @HapiTest
    final Stream<DynamicTest> allRequiredSigsAreChecked() {
        return defaultHapiSpec("AllRequiredSigsAreChecked")
                .given(
                        cryptoCreate(PAYER),
                        cryptoCreate(FIRST_TREASURY).balance(0L),
                        cryptoCreate(SECOND_TREASURY).balance(0L),
                        cryptoCreate(SPONSOR),
                        cryptoCreate(BENEFICIARY).receiverSigRequired(true))
                .when(
                        tokenCreate(A_TOKEN).initialSupply(123).treasury(FIRST_TREASURY),
                        tokenCreate(B_TOKEN).initialSupply(234).treasury(SECOND_TREASURY),
                        tokenAssociate(BENEFICIARY, A_TOKEN, B_TOKEN))
                .then(
                        cryptoTransfer(
                                        moving(100, A_TOKEN).between(FIRST_TREASURY, BENEFICIARY),
                                        moving(100, B_TOKEN).between(SECOND_TREASURY, BENEFICIARY),
                                        movingHbar(1_000).between(SPONSOR, FIRST_TREASURY))
                                .payingWith(PAYER)
                                .signedBy(PAYER, FIRST_TREASURY, BENEFICIARY, SPONSOR)
                                .fee(ONE_HUNDRED_HBARS)
                                .hasKnownStatus(INVALID_SIGNATURE),
                        cryptoTransfer(
                                        moving(100, A_TOKEN).between(FIRST_TREASURY, BENEFICIARY),
                                        moving(100, B_TOKEN).between(SECOND_TREASURY, BENEFICIARY),
                                        movingHbar(1_000).between(SPONSOR, FIRST_TREASURY))
                                .payingWith(PAYER)
                                .signedBy(PAYER, FIRST_TREASURY, SECOND_TREASURY, SPONSOR)
                                .fee(ONE_HUNDRED_HBARS)
                                .hasKnownStatus(INVALID_SIGNATURE),
                        cryptoTransfer(
                                        moving(100, A_TOKEN).between(FIRST_TREASURY, BENEFICIARY),
                                        moving(100, B_TOKEN).between(SECOND_TREASURY, BENEFICIARY),
                                        movingHbar(1_000).between(SPONSOR, FIRST_TREASURY))
                                .payingWith(PAYER)
                                .fee(ONE_HUNDRED_HBARS)
                                .signedBy(PAYER, FIRST_TREASURY, SECOND_TREASURY, BENEFICIARY)
                                .hasKnownStatus(INVALID_SIGNATURE),
                        cryptoTransfer(
                                        moving(100, A_TOKEN).between(FIRST_TREASURY, BENEFICIARY),
                                        moving(100, B_TOKEN).between(SECOND_TREASURY, BENEFICIARY),
                                        movingHbar(1_000).between(SPONSOR, FIRST_TREASURY))
                                .fee(ONE_HUNDRED_HBARS)
                                .payingWith(PAYER));
    }

    @HapiTest
    final Stream<DynamicTest> senderSigsAreValid() {
        return hapiTest(
                cryptoCreate(PAYER),
                cryptoCreate(FIRST_TREASURY).balance(0L),
                cryptoCreate(SECOND_TREASURY).balance(0L),
                cryptoCreate(BENEFICIARY),
                tokenCreate(A_TOKEN).initialSupply(123).treasury(FIRST_TREASURY),
                tokenCreate(B_TOKEN).initialSupply(234).treasury(SECOND_TREASURY),
                tokenAssociate(BENEFICIARY, A_TOKEN, B_TOKEN),
                balanceSnapshot("treasuryBefore", FIRST_TREASURY),
                balanceSnapshot("beneBefore", BENEFICIARY),
                cryptoTransfer(
                                moving(100, A_TOKEN).between(FIRST_TREASURY, BENEFICIARY),
                                movingHbar(ONE_HBAR).between(BENEFICIARY, FIRST_TREASURY))
                        .payingWith(PAYER)
                        .signedBy(FIRST_TREASURY, PAYER, BENEFICIARY)
                        .fee(ONE_HUNDRED_HBARS)
                        .via("transactTxn"),
                getAccountBalance(FIRST_TREASURY)
                        .hasTinyBars(changeFromSnapshot("treasuryBefore", +1 * ONE_HBAR))
                        .hasTokenBalance(A_TOKEN, 23),
                getAccountBalance(BENEFICIARY)
                        .hasTinyBars(changeFromSnapshot("beneBefore", -1 * ONE_HBAR))
                        .hasTokenBalance(A_TOKEN, 100),
                getTxnRecord("transactTxn"));
    }

    @HapiTest
    final Stream<DynamicTest> tokenPlusHbarTxnsAreAtomic() {
        return defaultHapiSpec("TokenPlusHbarTxnsAreAtomic")
                .given(
                        cryptoCreate(PAYER),
                        cryptoCreate(FIRST_TREASURY).balance(0L),
                        cryptoCreate(SECOND_TREASURY).balance(0L),
                        cryptoCreate(BENEFICIARY),
                        cryptoCreate("tbd").balance(0L))
                .when(
                        cryptoDelete("tbd"),
                        tokenCreate(A_TOKEN).initialSupply(123).treasury(FIRST_TREASURY),
                        tokenCreate(B_TOKEN).initialSupply(50).treasury(SECOND_TREASURY),
                        tokenAssociate(BENEFICIARY, A_TOKEN, B_TOKEN),
                        balanceSnapshot("before", BENEFICIARY),
                        cryptoTransfer(
                                        moving(100, A_TOKEN).between(FIRST_TREASURY, BENEFICIARY),
                                        moving(10, B_TOKEN).between(SECOND_TREASURY, BENEFICIARY),
                                        movingHbar(1).between(BENEFICIARY, "tbd"))
                                .fee(ONE_HUNDRED_HBARS)
                                .hasKnownStatus(ACCOUNT_DELETED))
                .then(
                        getAccountBalance(FIRST_TREASURY).logged().hasTokenBalance(A_TOKEN, 123),
                        getAccountBalance(SECOND_TREASURY).logged().hasTokenBalance(B_TOKEN, 50),
                        getAccountBalance(BENEFICIARY).logged().hasTinyBars(changeFromSnapshot("before", 0L)));
    }

    @HapiTest
    final Stream<DynamicTest> tokenOnlyTxnsAreAtomic() {
        return defaultHapiSpec("TokenOnlyTxnsAreAtomic")
                .given(
                        cryptoCreate(PAYER),
                        cryptoCreate(FIRST_TREASURY).balance(0L),
                        cryptoCreate(SECOND_TREASURY).balance(0L),
                        cryptoCreate(BENEFICIARY))
                .when(
                        tokenCreate(A_TOKEN).initialSupply(123).treasury(FIRST_TREASURY),
                        tokenCreate(B_TOKEN).initialSupply(50).treasury(SECOND_TREASURY),
                        tokenAssociate(BENEFICIARY, A_TOKEN, B_TOKEN),
                        cryptoTransfer(
                                        moving(100, A_TOKEN).between(FIRST_TREASURY, BENEFICIARY),
                                        moving(100, B_TOKEN).between(SECOND_TREASURY, BENEFICIARY))
                                .hasKnownStatus(INSUFFICIENT_TOKEN_BALANCE))
                .then(
                        getAccountBalance(FIRST_TREASURY).logged().hasTokenBalance(A_TOKEN, 123),
                        getAccountBalance(SECOND_TREASURY).logged().hasTokenBalance(B_TOKEN, 50),
                        getAccountBalance(BENEFICIARY).logged());
    }

    @HapiTest
    final Stream<DynamicTest> duplicateAccountsInTokenTransferRejected() {
        return defaultHapiSpec("DuplicateAccountsInTokenTransferRejected")
                .given(cryptoCreate(FIRST_TREASURY), cryptoCreate(FIRST_USER), cryptoCreate(SECOND_USER))
                .when(
                        tokenCreate(A_TOKEN).initialSupply(TOTAL_SUPPLY).treasury(FIRST_TREASURY),
                        tokenAssociate(FIRST_USER, A_TOKEN),
                        tokenAssociate(SECOND_USER, A_TOKEN))
                .then(cryptoTransfer(
                                moving(1, A_TOKEN).between(FIRST_TREASURY, FIRST_USER),
                                moving(1, A_TOKEN).between(FIRST_TREASURY, SECOND_USER))
                        .dontFullyAggregateTokenTransfers()
                        .hasPrecheck(ACCOUNT_REPEATED_IN_ACCOUNT_AMOUNTS));
    }

    @HapiTest
    final Stream<DynamicTest> nonZeroTransfersRejected() {
        return defaultHapiSpec("NonZeroTransfersRejected")
                .given(cryptoCreate(FIRST_TREASURY).balance(0L))
                .when(tokenCreate(A_TOKEN))
                .then(
                        cryptoTransfer(moving(1, A_TOKEN).from(FIRST_TREASURY))
                                .hasPrecheck(TRANSFERS_NOT_ZERO_SUM_FOR_TOKEN),
                        cryptoTransfer(movingHbar(1).from(FIRST_TREASURY)).hasPrecheck(INVALID_ACCOUNT_AMOUNTS));
    }

    @HapiTest
    final Stream<DynamicTest> balancesChangeOnTokenTransfer() {
        return defaultHapiSpec("BalancesChangeOnTokenTransfer")
                .given(
                        cryptoCreate(FIRST_USER).balance(0L),
                        cryptoCreate(SECOND_USER).balance(0L),
                        cryptoCreate(TOKEN_TREASURY).balance(0L),
                        tokenCreate(A_TOKEN).initialSupply(TOTAL_SUPPLY).treasury(TOKEN_TREASURY),
                        tokenCreate(B_TOKEN).initialSupply(TOTAL_SUPPLY).treasury(TOKEN_TREASURY),
                        tokenAssociate(FIRST_USER, A_TOKEN),
                        tokenAssociate(SECOND_USER, A_TOKEN),
                        tokenAssociate(SECOND_USER, B_TOKEN))
                .when(cryptoTransfer(
                        moving(100, A_TOKEN).between(TOKEN_TREASURY, FIRST_USER),
                        moving(100, A_TOKEN).between(TOKEN_TREASURY, SECOND_USER),
                        moving(100, B_TOKEN).between(TOKEN_TREASURY, SECOND_USER)))
                .then(
                        getAccountBalance(TOKEN_TREASURY)
                                .hasTokenBalance(A_TOKEN, TOTAL_SUPPLY - 200)
                                .hasTokenBalance(B_TOKEN, TOTAL_SUPPLY - 100),
                        getAccountBalance(FIRST_USER).hasTokenBalance(A_TOKEN, 100),
                        getAccountBalance(SECOND_USER)
                                .hasTokenBalance(B_TOKEN, 100)
                                .hasTokenBalance(A_TOKEN, 100));
    }

    @HapiTest
    final Stream<DynamicTest> uniqueTokenTxnAccountBalance() {
        return defaultHapiSpec("UniqueTokenTxnAccountBalance")
                .given(
                        newKeyNamed(SUPPLY_KEY),
                        newKeyNamed(SIGNING_KEY_TREASURY),
                        newKeyNamed(SIGNING_KEY_FIRST_USER),
                        cryptoCreate(FIRST_USER).key(SIGNING_KEY_FIRST_USER),
                        cryptoCreate(TOKEN_TREASURY).key(SIGNING_KEY_TREASURY),
                        tokenCreate(A_TOKEN)
                                .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                                .initialSupply(0)
                                .supplyKey(SUPPLY_KEY)
                                .treasury(TOKEN_TREASURY),
                        mintToken(A_TOKEN, List.of(copyFromUtf8("memo"))),
                        tokenAssociate(FIRST_USER, A_TOKEN))
                .when(cryptoTransfer(movingUnique(A_TOKEN, 1).between(TOKEN_TREASURY, FIRST_USER))
                        .signedBy(SIGNING_KEY_TREASURY, SIGNING_KEY_FIRST_USER, DEFAULT_PAYER)
                        .via("cryptoTransferTxn"))
                .then(
                        getAccountBalance(TOKEN_TREASURY).hasTokenBalance(A_TOKEN, 0),
                        getAccountBalance(FIRST_USER).hasTokenBalance(A_TOKEN, 1),
                        withTargetLedgerId(ledgerId -> getTokenInfo(A_TOKEN).hasEncodedLedgerId(ledgerId)),
                        getTokenNftInfo(A_TOKEN, 1)
                                .hasSerialNum(1)
                                .hasMetadata(copyFromUtf8("memo"))
                                .hasTokenID(A_TOKEN)
                                .hasAccountID(FIRST_USER));
    }

    @HapiTest
    final Stream<DynamicTest> uniqueTokenTxnAccountBalancesForTreasury() {
        return defaultHapiSpec("UniqueTokenTxnAccountBalancesForTreasury")
                .given(
                        newKeyNamed(SUPPLY_KEY),
                        cryptoCreate(NEW_TREASURY),
                        cryptoCreate(OLD_TREASURY),
                        tokenCreate(A_TOKEN)
                                .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                                .initialSupply(0)
                                .supplyKey(SUPPLY_KEY)
                                .treasury(OLD_TREASURY),
                        tokenCreate(B_TOKEN)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .initialSupply(0)
                                .adminKey(SUPPLY_KEY)
                                .supplyKey(SUPPLY_KEY)
                                .treasury(OLD_TREASURY),
                        mintToken(A_TOKEN, List.of(copyFromUtf8("memo"))),
                        mintToken(B_TOKEN, List.of(copyFromUtf8("memo2"))),
                        tokenAssociate(NEW_TREASURY, A_TOKEN, B_TOKEN),
                        tokenUpdate(B_TOKEN)
                                .treasury(NEW_TREASURY)
                                .signedByPayerAnd(SUPPLY_KEY, NEW_TREASURY)
                                .hasKnownStatus(SUCCESS))
                .when(cryptoTransfer(movingUnique(A_TOKEN, 1).between(OLD_TREASURY, NEW_TREASURY))
                        .via("cryptoTransferTxn"))
                .then(
                        getAccountBalance(OLD_TREASURY).hasTokenBalance(A_TOKEN, 0),
                        getAccountBalance(NEW_TREASURY).hasTokenBalance(A_TOKEN, 1),
                        getAccountBalance(NEW_TREASURY).hasTokenBalance(B_TOKEN, 1),
                        withTargetLedgerId(ledgerId -> getTokenNftInfo(A_TOKEN, 1)
                                .hasEncodedLedgerId(ledgerId)
                                .hasSerialNum(1)
                                .hasMetadata(copyFromUtf8("memo"))
                                .hasTokenID(A_TOKEN)
                                .hasAccountID(NEW_TREASURY)));
    }

    @HapiTest
    final Stream<DynamicTest> uniqueTokenTxnWithNoAssociation() {
        return defaultHapiSpec("UniqueTokenTxnWithNoAssociation")
                .given(
                        cryptoCreate(TOKEN_TREASURY),
                        cryptoCreate(FIRST_USER),
                        newKeyNamed(SUPPLY_KEY),
                        tokenCreate(A_TOKEN)
                                .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                                .initialSupply(0)
                                .supplyKey(SUPPLY_KEY)
                                .treasury(TOKEN_TREASURY))
                .when(mintToken(A_TOKEN, List.of(copyFromUtf8("memo"))))
                .then(cryptoTransfer(movingUnique(A_TOKEN, 1).between(TOKEN_TREASURY, FIRST_USER))
                        .hasKnownStatus(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT));
    }

    @HapiTest
    final Stream<DynamicTest> uniqueTokenTxnWithFrozenAccount() {
        return hapiTest(
                cryptoCreate(TOKEN_TREASURY).balance(0L),
                cryptoCreate(FIRST_USER).balance(0L),
                newKeyNamed(FREEZE_KEY),
                newKeyNamed(SUPPLY_KEY),
                tokenCreate(A_TOKEN)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0)
                        .freezeKey(FREEZE_KEY)
                        .freezeDefault(true)
                        .supplyKey(SUPPLY_KEY)
                        .treasury(TOKEN_TREASURY),
                tokenAssociate(FIRST_USER, A_TOKEN),
                mintToken(A_TOKEN, List.of(copyFromUtf8("memo"))),
                cryptoTransfer(movingUnique(A_TOKEN, 1).between(TOKEN_TREASURY, FIRST_USER))
                        .hasKnownStatus(ACCOUNT_FROZEN_FOR_TOKEN));
    }

    @HapiTest
    final Stream<DynamicTest> uniqueTokenTxnWithSenderNotSigned() {
        return defaultHapiSpec("uniqueTokenTxnWithSenderNotSigned")
                .given(
                        newKeyNamed(SUPPLY_KEY),
                        newKeyNamed(SIGNING_KEY_TREASURY),
                        cryptoCreate(TOKEN_TREASURY).key(SIGNING_KEY_TREASURY),
                        cryptoCreate(FIRST_USER),
                        tokenCreate(A_TOKEN)
                                .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                                .initialSupply(0)
                                .supplyKey(SUPPLY_KEY)
                                .treasury(TOKEN_TREASURY),
                        tokenAssociate(FIRST_USER, A_TOKEN))
                .when(mintToken(A_TOKEN, List.of(copyFromUtf8("memo"))))
                .then(cryptoTransfer(movingUnique(A_TOKEN, 1).between(TOKEN_TREASURY, FIRST_USER))
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(INVALID_SIGNATURE));
    }

    @HapiTest
    final Stream<DynamicTest> uniqueTokenTxnWithReceiverNotSigned() {
        return defaultHapiSpec("uniqueTokenTxnWithReceiverNotSigned")
                .given(
                        newKeyNamed(SUPPLY_KEY),
                        newKeyNamed(SIGNING_KEY_TREASURY),
                        newKeyNamed(SIGNING_KEY_FIRST_USER),
                        cryptoCreate(TOKEN_TREASURY).key(SIGNING_KEY_TREASURY),
                        cryptoCreate(FIRST_USER).key(SIGNING_KEY_FIRST_USER).receiverSigRequired(true),
                        tokenCreate(A_TOKEN)
                                .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                                .initialSupply(0)
                                .supplyKey(SUPPLY_KEY)
                                .treasury(TOKEN_TREASURY),
                        tokenAssociate(FIRST_USER, A_TOKEN))
                .when(mintToken(A_TOKEN, List.of(copyFromUtf8("memo"))))
                .then(cryptoTransfer(movingUnique(A_TOKEN, 1).between(TOKEN_TREASURY, FIRST_USER))
                        .signedBy(SIGNING_KEY_TREASURY, DEFAULT_PAYER)
                        .hasKnownStatus(INVALID_SIGNATURE));
    }

    @HapiTest
    final Stream<DynamicTest> uniqueTokenTxnsAreAtomic() {
        return defaultHapiSpec("UniqueTokenTxnsAreAtomic")
                .given(
                        newKeyNamed(SUPPLY_KEY),
                        newKeyNamed(SIGNING_KEY_TREASURY),
                        newKeyNamed(SIGNING_KEY_FIRST_USER),
                        cryptoCreate(FIRST_USER).key(SIGNING_KEY_FIRST_USER),
                        cryptoCreate(SECOND_USER),
                        cryptoCreate(TOKEN_TREASURY).key(SIGNING_KEY_TREASURY),
                        tokenCreate(A_TOKEN)
                                .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                                .initialSupply(0)
                                .supplyKey(SUPPLY_KEY)
                                .treasury(TOKEN_TREASURY),
                        tokenCreate(B_TOKEN).initialSupply(100).treasury(TOKEN_TREASURY),
                        mintToken(A_TOKEN, List.of(copyFromUtf8("memo"))),
                        tokenAssociate(FIRST_USER, A_TOKEN),
                        tokenAssociate(FIRST_USER, B_TOKEN),
                        tokenAssociate(SECOND_USER, A_TOKEN))
                .when(cryptoTransfer(
                                movingUnique(A_TOKEN, 1).between(TOKEN_TREASURY, SECOND_USER),
                                moving(101, B_TOKEN).between(TOKEN_TREASURY, FIRST_USER))
                        .hasKnownStatus(INSUFFICIENT_TOKEN_BALANCE))
                .then(
                        getAccountBalance(TOKEN_TREASURY).hasTokenBalance(A_TOKEN, 1),
                        getAccountBalance(TOKEN_TREASURY).hasTokenBalance(B_TOKEN, 100),
                        getAccountBalance(FIRST_USER).hasTokenBalance(A_TOKEN, 0),
                        getAccountBalance(SECOND_USER).hasTokenBalance(A_TOKEN, 0));
    }

    @HapiTest
    final Stream<DynamicTest> uniqueTokenDeletedTxn() {
        return defaultHapiSpec("UniqueTokenDeletedTxn")
                .given(
                        newKeyNamed(SUPPLY_KEY),
                        newKeyNamed("nftAdmin"),
                        newKeyNamed(SIGNING_KEY_TREASURY),
                        newKeyNamed(SIGNING_KEY_FIRST_USER),
                        cryptoCreate(FIRST_USER).key(SIGNING_KEY_FIRST_USER),
                        cryptoCreate(TOKEN_TREASURY).key(SIGNING_KEY_TREASURY),
                        tokenCreate(A_TOKEN)
                                .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                                .initialSupply(0)
                                .supplyKey(SUPPLY_KEY)
                                .adminKey("nftAdmin")
                                .treasury(TOKEN_TREASURY),
                        mintToken(A_TOKEN, List.of(copyFromUtf8("memo"))),
                        tokenAssociate(FIRST_USER, A_TOKEN))
                .when(tokenDelete(A_TOKEN))
                .then(cryptoTransfer(movingUnique(A_TOKEN, 1).between(TOKEN_TREASURY, FIRST_USER))
                        .signedBy(SIGNING_KEY_TREASURY, SIGNING_KEY_FIRST_USER, DEFAULT_PAYER)
                        .hasKnownStatus(TOKEN_WAS_DELETED));
    }

    @HapiTest
    final Stream<DynamicTest> fractionalFeeTokenPutInNonFungibleTransferListIsRejectedSafely() {
        return hapiTest(
                cryptoCreate(CIVILIAN),
                cryptoCreate("receiver").maxAutomaticTokenAssociations(5),
                tokenCreate("fractionalFeeToken")
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(Long.MAX_VALUE)
                        .treasury(DEFAULT_PAYER)
                        .withCustom(fractionalFee(1, 10, 0, OptionalLong.empty(), DEFAULT_PAYER)),
                cryptoTransfer((spec, b) -> {
                            final var registry = spec.registry();
                            b.addTokenTransfers(TokenTransferList.newBuilder()
                                    .setToken(registry.getTokenID("fractionalFeeToken"))
                                    .addAllNftTransfers(List.of(NftTransfer.newBuilder()
                                            .setSenderAccountID(registry.getAccountID(CIVILIAN))
                                            .setReceiverAccountID(registry.getAccountID("receiver"))
                                            .setSerialNumber(123L)
                                            .build()))
                                    .build());
                        })
                        .fee(ONE_HUNDRED_HBARS)
                        .signedBy(DEFAULT_PAYER, CIVILIAN)
                        .hasKnownStatus(NFT_TRANSFERS_ONLY_ALLOWED_FOR_NON_FUNGIBLE_UNIQUE));
    }

    @HapiTest
    final Stream<DynamicTest> fixedHbarCaseStudy() {
        final var alice = "Alice";
        final var bob = "Bob";
        final var tokenWithHbarFee = "TokenWithHbarFee";
        final var treasuryForToken = TOKEN_TREASURY;
        final var supplyKey = "antique";

        final var txnFromTreasury = TXN_FROM_TREASURY;
        final var txnFromAlice = "txnFromAlice";

        return defaultHapiSpec("FixedHbarCaseStudy")
                .given(
                        newKeyNamed(supplyKey),
                        cryptoCreate(alice).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(bob),
                        cryptoCreate(treasuryForToken).balance(ONE_HUNDRED_HBARS),
                        tokenCreate(tokenWithHbarFee)
                                .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                                .supplyKey(supplyKey)
                                .initialSupply(0L)
                                .treasury(treasuryForToken)
                                .withCustom(fixedHbarFee(ONE_HBAR, treasuryForToken)),
                        mintToken(tokenWithHbarFee, List.of(copyFromUtf8("First!"))),
                        mintToken(tokenWithHbarFee, List.of(copyFromUtf8("Second!"))),
                        tokenAssociate(alice, tokenWithHbarFee),
                        tokenAssociate(bob, tokenWithHbarFee),
                        cryptoTransfer(movingUnique(tokenWithHbarFee, 2L).between(treasuryForToken, alice))
                                .payingWith(GENESIS)
                                .fee(ONE_HBAR)
                                .via(txnFromTreasury))
                .when(cryptoTransfer(movingUnique(tokenWithHbarFee, 2L).between(alice, bob))
                        .payingWith(GENESIS)
                        .fee(ONE_HBAR)
                        .via(txnFromAlice))
                .then(
                        getTxnRecord(txnFromTreasury).hasNftTransfer(tokenWithHbarFee, treasuryForToken, alice, 2L),
                        getTxnRecord(txnFromAlice)
                                .hasNftTransfer(tokenWithHbarFee, alice, bob, 2L)
                                .hasAssessedCustomFee(HBAR_TOKEN_SENTINEL, treasuryForToken, ONE_HBAR)
                                .hasHbarAmount(treasuryForToken, ONE_HBAR)
                                .hasHbarAmount(alice, -ONE_HBAR),
                        getAccountBalance(bob).hasTokenBalance(tokenWithHbarFee, 1L),
                        getAccountBalance(alice)
                                .hasTokenBalance(tokenWithHbarFee, 0L)
                                .hasTinyBars(ONE_HUNDRED_HBARS - ONE_HBAR),
                        getAccountBalance(treasuryForToken)
                                .hasTokenBalance(tokenWithHbarFee, 1L)
                                .hasTinyBars(ONE_HUNDRED_HBARS + ONE_HBAR));
    }

    @HapiTest
    final Stream<DynamicTest> fractionalCaseStudy() {
        final var alice = "Alice";
        final var bob = "Bob";
        final var tokenWithFractionalFee = TOKEN_WITH_FRACTIONAL_FEE;
        final var treasuryForToken = TOKEN_TREASURY;

        final var txnFromTreasury = TXN_FROM_TREASURY;
        final var txnFromBob = "txnFromBob";

        return defaultHapiSpec("FractionalCaseStudy")
                .given(
                        cryptoCreate(alice),
                        cryptoCreate(bob),
                        cryptoCreate(treasuryForToken),
                        tokenCreate(tokenWithFractionalFee)
                                .initialSupply(Long.MAX_VALUE)
                                .treasury(treasuryForToken)
                                .withCustom(fractionalFee(1L, 100L, 1L, OptionalLong.of(5L), treasuryForToken)),
                        tokenAssociate(alice, tokenWithFractionalFee),
                        tokenAssociate(bob, tokenWithFractionalFee),
                        cryptoTransfer(moving(1_000_000L, tokenWithFractionalFee)
                                        .between(treasuryForToken, bob))
                                .payingWith(treasuryForToken)
                                .fee(ONE_HBAR)
                                .via(txnFromTreasury))
                .when(cryptoTransfer(moving(1_000L, tokenWithFractionalFee).between(bob, alice))
                        .payingWith(bob)
                        .fee(ONE_HBAR)
                        .via(txnFromBob))
                .then(
                        getTxnRecord(txnFromTreasury)
                                .hasTokenAmount(tokenWithFractionalFee, bob, 1_000_000L)
                                .hasTokenAmount(tokenWithFractionalFee, treasuryForToken, -1_000_000L),
                        getTxnRecord(txnFromBob)
                                .hasTokenAmount(tokenWithFractionalFee, bob, -1_000L)
                                .hasTokenAmount(tokenWithFractionalFee, alice, 995L)
                                .hasAssessedCustomFee(tokenWithFractionalFee, treasuryForToken, 5L)
                                .hasTokenAmount(tokenWithFractionalFee, treasuryForToken, 5L),
                        getAccountBalance(alice).hasTokenBalance(tokenWithFractionalFee, 995L),
                        getAccountBalance(bob).hasTokenBalance(tokenWithFractionalFee, 1_000_000L - 1_000L),
                        getAccountBalance(treasuryForToken)
                                .hasTokenBalance(tokenWithFractionalFee, Long.MAX_VALUE - 1_000_000L + 5L));
    }

    @HapiTest
    final Stream<DynamicTest> fractionalNetOfTransfersCaseStudy() {
        final var gerry = "gerry";
        final var horace = "horace";
        final var useCaseToken = TOKEN_WITH_FRACTIONAL_FEE;
        final var treasuryForToken = TOKEN_TREASURY;

        final var txnFromTreasury = TXN_FROM_TREASURY;
        final var txnFromHorace = "txnFromHorace";

        return defaultHapiSpec("FractionalNetOfTransfersCaseStudy")
                .given(
                        cryptoCreate(gerry),
                        cryptoCreate(horace),
                        cryptoCreate(treasuryForToken),
                        tokenCreate(useCaseToken)
                                .initialSupply(Long.MAX_VALUE)
                                .treasury(treasuryForToken)
                                .withCustom(fractionalFeeNetOfTransfers(
                                        1L, 100L, 1L, OptionalLong.of(5L), treasuryForToken)),
                        tokenAssociate(gerry, useCaseToken),
                        tokenAssociate(horace, useCaseToken),
                        cryptoTransfer(moving(1_000_000L, useCaseToken).between(treasuryForToken, horace))
                                .payingWith(treasuryForToken)
                                .fee(ONE_HBAR)
                                .via(txnFromTreasury))
                .when(cryptoTransfer(moving(1_000L, useCaseToken).between(horace, gerry))
                        .payingWith(horace)
                        .fee(ONE_HBAR)
                        .via(txnFromHorace))
                .then(
                        getTxnRecord(txnFromTreasury)
                                .hasTokenAmount(useCaseToken, horace, 1_000_000L)
                                .hasTokenAmount(useCaseToken, treasuryForToken, -1_000_000L),
                        getTxnRecord(txnFromHorace)
                                .hasTokenAmount(useCaseToken, horace, -1_005L)
                                .hasTokenAmount(useCaseToken, gerry, 1000L)
                                .hasAssessedCustomFee(useCaseToken, treasuryForToken, 5L)
                                .hasTokenAmount(useCaseToken, treasuryForToken, 5L),
                        getAccountBalance(gerry).hasTokenBalance(useCaseToken, 1000L),
                        getAccountBalance(horace).hasTokenBalance(useCaseToken, 1_000_000L - 1_005L),
                        getAccountBalance(treasuryForToken)
                                .hasTokenBalance(useCaseToken, Long.MAX_VALUE - 1_000_000L + 5L));
    }

    @HapiTest
    final Stream<DynamicTest> simpleHtsFeeCaseStudy() {
        final var claire = "Claire";
        final var debbie = DEBBIE;
        final var simpleHtsFeeToken = "SimpleHtsFeeToken";
        final var commissionPaymentToken = "commissionPaymentToken";
        final var treasuryForToken = TOKEN_TREASURY;

        final var txnFromTreasury = TXN_FROM_TREASURY;
        final var txnFromClaire = "txnFromClaire";

        return defaultHapiSpec("SimpleHtsFeeCaseStudy")
                .given(
                        cryptoCreate(claire),
                        cryptoCreate(debbie),
                        cryptoCreate(treasuryForToken),
                        tokenCreate(commissionPaymentToken)
                                .initialSupply(Long.MAX_VALUE)
                                .treasury(treasuryForToken),
                        tokenCreate(simpleHtsFeeToken)
                                .initialSupply(Long.MAX_VALUE)
                                .treasury(treasuryForToken)
                                .withCustom(fixedHtsFee(2L, commissionPaymentToken, treasuryForToken)),
                        tokenAssociate(claire, List.of(simpleHtsFeeToken, commissionPaymentToken)),
                        tokenAssociate(debbie, simpleHtsFeeToken),
                        cryptoTransfer(
                                        moving(1_000L, commissionPaymentToken).between(treasuryForToken, claire),
                                        moving(1_000L, simpleHtsFeeToken).between(treasuryForToken, claire))
                                .payingWith(treasuryForToken)
                                .fee(ONE_HBAR)
                                .via(txnFromTreasury))
                .when(cryptoTransfer(moving(100L, simpleHtsFeeToken).between(claire, debbie))
                        .payingWith(claire)
                        .fee(ONE_HBAR)
                        .via(txnFromClaire))
                .then(
                        getTxnRecord(txnFromTreasury)
                                .hasTokenAmount(commissionPaymentToken, claire, 1_000L)
                                .hasTokenAmount(commissionPaymentToken, treasuryForToken, -1_000L)
                                .hasTokenAmount(simpleHtsFeeToken, claire, 1_000L)
                                .hasTokenAmount(simpleHtsFeeToken, treasuryForToken, -1_000L),
                        getTxnRecord(txnFromClaire)
                                .hasTokenAmount(simpleHtsFeeToken, debbie, 100L)
                                .hasTokenAmount(simpleHtsFeeToken, claire, -100L)
                                .hasAssessedCustomFee(commissionPaymentToken, treasuryForToken, 2L)
                                .hasTokenAmount(commissionPaymentToken, treasuryForToken, 2L)
                                .hasTokenAmount(commissionPaymentToken, claire, -2L),
                        getAccountBalance(debbie).hasTokenBalance(simpleHtsFeeToken, 100L),
                        getAccountBalance(claire)
                                .hasTokenBalance(simpleHtsFeeToken, 1_000L - 100L)
                                .hasTokenBalance(commissionPaymentToken, 1_000L - 2L),
                        getAccountBalance(treasuryForToken)
                                .hasTokenBalance(simpleHtsFeeToken, Long.MAX_VALUE - 1_000L)
                                .hasTokenBalance(commissionPaymentToken, Long.MAX_VALUE - 1_000L + 2L));
    }

    @HapiTest
    final Stream<DynamicTest> nestedHbarCaseStudy() {
        final var debbie = DEBBIE;
        final var edgar = EDGAR;
        final var tokenWithHbarFee = "TokenWithHbarFee";
        final var tokenWithNestedFee = TOKEN_WITH_NESTED_FEE;
        final var treasuryForTopLevelCollection = TOKEN_TREASURY;
        final var treasuryForNestedCollection = NESTED_TOKEN_TREASURY;

        final var txnFromTreasury = TXN_FROM_TREASURY;
        final var txnFromDebbie = "txnFromDebbie";

        return defaultHapiSpec("NestedHbarCaseStudy")
                .given(
                        cryptoCreate(debbie).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(edgar),
                        cryptoCreate(treasuryForTopLevelCollection),
                        cryptoCreate(treasuryForNestedCollection).balance(ONE_HUNDRED_HBARS),
                        tokenCreate(tokenWithHbarFee)
                                .initialSupply(Long.MAX_VALUE)
                                .treasury(treasuryForNestedCollection)
                                .withCustom(fixedHbarFee(ONE_HBAR, treasuryForNestedCollection)),
                        tokenAssociate(treasuryForTopLevelCollection, tokenWithHbarFee),
                        tokenCreate(tokenWithNestedFee)
                                .initialSupply(Long.MAX_VALUE)
                                .treasury(treasuryForTopLevelCollection)
                                .withCustom(fixedHtsFee(1L, tokenWithHbarFee, treasuryForTopLevelCollection)),
                        tokenAssociate(debbie, List.of(tokenWithHbarFee, tokenWithNestedFee)),
                        tokenAssociate(edgar, tokenWithNestedFee),
                        cryptoTransfer(
                                        moving(1_000L, tokenWithHbarFee).between(treasuryForNestedCollection, debbie),
                                        moving(1_000L, tokenWithNestedFee)
                                                .between(treasuryForTopLevelCollection, debbie))
                                .payingWith(GENESIS)
                                .fee(ONE_HBAR)
                                .via(txnFromTreasury))
                .when(cryptoTransfer(moving(1L, tokenWithNestedFee).between(debbie, edgar))
                        .payingWith(GENESIS)
                        .fee(ONE_HBAR)
                        .via(txnFromDebbie))
                .then(
                        getTxnRecord(txnFromTreasury)
                                .hasTokenAmount(tokenWithHbarFee, debbie, 1_000L)
                                .hasTokenAmount(tokenWithHbarFee, treasuryForNestedCollection, -1_000L)
                                .hasTokenAmount(tokenWithNestedFee, debbie, 1_000L)
                                .hasTokenAmount(tokenWithNestedFee, treasuryForTopLevelCollection, -1_000L),
                        getTxnRecord(txnFromDebbie)
                                .hasTokenAmount(tokenWithNestedFee, edgar, 1L)
                                .hasTokenAmount(tokenWithNestedFee, debbie, -1L)
                                .hasAssessedCustomFee(tokenWithHbarFee, treasuryForTopLevelCollection, 1L)
                                .hasTokenAmount(tokenWithHbarFee, treasuryForTopLevelCollection, 1L)
                                .hasTokenAmount(tokenWithHbarFee, debbie, -1L)
                                .hasAssessedCustomFee(HBAR_TOKEN_SENTINEL, treasuryForNestedCollection, ONE_HBAR)
                                .hasHbarAmount(treasuryForNestedCollection, ONE_HBAR)
                                .hasHbarAmount(debbie, -ONE_HBAR),
                        getAccountBalance(edgar).hasTokenBalance(tokenWithNestedFee, 1L),
                        getAccountBalance(debbie)
                                .hasTinyBars(ONE_HUNDRED_HBARS - ONE_HBAR)
                                .hasTokenBalance(tokenWithHbarFee, 1_000L - 1L)
                                .hasTokenBalance(tokenWithNestedFee, 1_000L - 1L),
                        getAccountBalance(treasuryForTopLevelCollection)
                                .hasTokenBalance(tokenWithNestedFee, Long.MAX_VALUE - 1_000L)
                                .hasTokenBalance(tokenWithHbarFee, 1L),
                        getAccountBalance(treasuryForNestedCollection)
                                .hasTinyBars(ONE_HUNDRED_HBARS + ONE_HBAR)
                                .hasTokenBalance(tokenWithHbarFee, Long.MAX_VALUE - 1_000L));
    }

    @HapiTest
    final Stream<DynamicTest> nestedFractionalCaseStudy() {
        final var edgar = EDGAR;
        final var fern = "Fern";
        final var tokenWithFractionalFee = TOKEN_WITH_FRACTIONAL_FEE;
        final var tokenWithNestedFee = TOKEN_WITH_NESTED_FEE;
        final var treasuryForTopLevelCollection = TOKEN_TREASURY;
        final var treasuryForNestedCollection = NESTED_TOKEN_TREASURY;

        final var txnFromTreasury = TXN_FROM_TREASURY;
        final var txnFromEdgar = "txnFromEdgar";

        return hapiTest(
                cryptoCreate(edgar),
                cryptoCreate(fern),
                cryptoCreate(treasuryForTopLevelCollection),
                cryptoCreate(treasuryForNestedCollection),
                tokenCreate(tokenWithFractionalFee)
                        .initialSupply(Long.MAX_VALUE)
                        .treasury(treasuryForNestedCollection)
                        .withCustom(fractionalFee(1L, 100L, 1L, OptionalLong.of(5L), treasuryForNestedCollection)),
                tokenAssociate(treasuryForTopLevelCollection, tokenWithFractionalFee),
                tokenCreate(tokenWithNestedFee)
                        .initialSupply(Long.MAX_VALUE)
                        .treasury(treasuryForTopLevelCollection)
                        .withCustom(fixedHtsFee(50L, tokenWithFractionalFee, treasuryForTopLevelCollection)),
                tokenAssociate(edgar, List.of(tokenWithFractionalFee, tokenWithNestedFee)),
                tokenAssociate(fern, tokenWithNestedFee),
                cryptoTransfer(
                                moving(1_000L, tokenWithFractionalFee).between(treasuryForNestedCollection, edgar),
                                moving(1_000L, tokenWithNestedFee).between(treasuryForTopLevelCollection, edgar))
                        .payingWith(treasuryForNestedCollection)
                        .fee(ONE_HBAR)
                        .via(txnFromTreasury),
                cryptoTransfer(moving(10L, tokenWithNestedFee).between(edgar, fern))
                        .payingWith(edgar)
                        .fee(ONE_HBAR)
                        .via(txnFromEdgar),
                getTxnRecord(txnFromTreasury)
                        .hasTokenAmount(tokenWithFractionalFee, edgar, 1_000L)
                        .hasTokenAmount(tokenWithFractionalFee, treasuryForNestedCollection, -1_000L)
                        .hasTokenAmount(tokenWithNestedFee, edgar, 1_000L)
                        .hasTokenAmount(tokenWithNestedFee, treasuryForTopLevelCollection, -1_000L),
                getTxnRecord(txnFromEdgar)
                        .hasTokenAmount(tokenWithNestedFee, fern, 10L)
                        .hasTokenAmount(tokenWithNestedFee, edgar, -10L)
                        .hasAssessedCustomFee(tokenWithFractionalFee, treasuryForTopLevelCollection, 50L)
                        .hasTokenAmount(tokenWithFractionalFee, treasuryForTopLevelCollection, 49L)
                        .hasTokenAmount(tokenWithFractionalFee, edgar, -50L)
                        .hasAssessedCustomFee(tokenWithFractionalFee, treasuryForNestedCollection, 1L)
                        .hasTokenAmount(tokenWithFractionalFee, treasuryForNestedCollection, 1L),
                getAccountBalance(fern).hasTokenBalance(tokenWithNestedFee, 10L),
                getAccountBalance(edgar)
                        .hasTokenBalance(tokenWithNestedFee, 1_000L - 10L)
                        .hasTokenBalance(tokenWithFractionalFee, 1_000L - 50L),
                getAccountBalance(treasuryForTopLevelCollection)
                        .hasTokenBalance(tokenWithNestedFee, Long.MAX_VALUE - 1_000L)
                        .hasTokenBalance(tokenWithFractionalFee, 49L),
                getAccountBalance(treasuryForNestedCollection)
                        .hasTokenBalance(tokenWithFractionalFee, Long.MAX_VALUE - 1_000L + 1L));
    }

    @HapiTest
    final Stream<DynamicTest> multipleRoyaltyFallbackCaseStudy() {
        final var zephyr = "zephyr";
        final var amelie = AMELIE;
        final var usdcTreasury = "bank";
        final var westWindTreasury = COLLECTION;
        final var westWindArt = WEST_WIND_ART;
        final var westWindDirector = "director";
        final var westWindOwner = "owner";
        final var usdc = "USDC";
        final var supplyKey = SUPPLY;

        final var txnFromTreasury = TXN_FROM_TREASURY;
        final var txnFromZephyr = "txnFromZephyr";

        return hapiTest(
                newKeyNamed(supplyKey),
                cryptoCreate(zephyr),
                cryptoCreate(amelie),
                cryptoCreate(usdcTreasury),
                cryptoCreate(westWindTreasury),
                cryptoCreate(westWindDirector),
                cryptoCreate(westWindOwner),
                tokenCreate(usdc).treasury(usdcTreasury),
                tokenAssociate(westWindTreasury, usdc),
                tokenAssociate(westWindOwner, usdc),
                tokenCreate(westWindArt)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0)
                        .supplyKey(supplyKey)
                        .treasury(westWindTreasury)
                        .withCustom(royaltyFeeWithFallback(
                                10, 100, fixedHtsFeeInheritingRoyaltyCollector(1, usdc), westWindTreasury))
                        .withCustom(royaltyFeeNoFallback(10, 100, westWindDirector))
                        .withCustom(royaltyFeeWithFallback(
                                5, 100, fixedHtsFeeInheritingRoyaltyCollector(1, usdc), westWindOwner)),
                tokenAssociate(amelie, List.of(westWindArt, usdc)),
                tokenAssociate(zephyr, List.of(westWindArt, usdc)),
                mintToken(westWindArt, List.of(copyFromUtf8(FUGUES_AND_FANTASTICS))),
                cryptoTransfer(movingUnique(westWindArt, 1L).between(westWindTreasury, zephyr))
                        .fee(ONE_HBAR)
                        .via(txnFromTreasury),
                cryptoTransfer(movingUnique(westWindArt, 1L).between(zephyr, amelie))
                        .payingWith(zephyr)
                        .fee(ONE_HBAR)
                        .hasKnownStatus(INVALID_SIGNATURE),
                cryptoTransfer(movingUnique(westWindArt, 1L).between(zephyr, amelie))
                        .signedBy(amelie, zephyr)
                        .payingWith(zephyr)
                        .fee(ONE_HBAR)
                        .hasKnownStatus(INSUFFICIENT_SENDER_ACCOUNT_BALANCE_FOR_CUSTOM_FEE),
                cryptoTransfer(moving(2, usdc).between(usdcTreasury, amelie)),
                cryptoTransfer(movingUnique(westWindArt, 1L).between(zephyr, amelie))
                        .signedBy(amelie, zephyr)
                        .payingWith(zephyr)
                        .fee(ONE_HBAR)
                        .via(txnFromZephyr),
                getTxnRecord(txnFromTreasury),
                getTxnRecord(txnFromZephyr));
    }

    @HapiTest
    final Stream<DynamicTest> respondsCorrectlyWhenNonFungibleTokenWithRoyaltyUsedInTransferList() {
        final var supplyKey = "misc";
        final var nonfungible = "nonfungible";
        final var beneficiary = BENEFICIARY;

        return defaultHapiSpec("RespondsCorrectlyWhenNonFungibleTokenWithRoyaltyUsedInTransferList")
                .given(
                        cryptoCreate(CIVILIAN).maxAutomaticTokenAssociations(10),
                        cryptoCreate(beneficiary).maxAutomaticTokenAssociations(10),
                        cryptoCreate(TOKEN_TREASURY),
                        newKeyNamed(supplyKey),
                        tokenCreate(nonfungible)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .supplyKey(supplyKey)
                                .initialSupply(0L)
                                .withCustom(royaltyFeeWithFallback(
                                        1, 2, fixedHbarFeeInheritingRoyaltyCollector(100), TOKEN_TREASURY))
                                .treasury(TOKEN_TREASURY),
                        mintToken(nonfungible, List.of(copyFromUtf8("a"), copyFromUtf8("aa"), copyFromUtf8("aaa"))))
                .when(cryptoTransfer(movingUnique(nonfungible, 1L, 2L, 3L).between(TOKEN_TREASURY, CIVILIAN))
                        .signedBy(DEFAULT_PAYER, TOKEN_TREASURY, CIVILIAN)
                        .fee(ONE_HBAR))
                .then(cryptoTransfer(moving(1, nonfungible).between(CIVILIAN, beneficiary))
                        .signedBy(DEFAULT_PAYER, CIVILIAN, beneficiary)
                        .fee(ONE_HBAR)
                        .hasKnownStatus(ACCOUNT_AMOUNT_TRANSFERS_ONLY_ALLOWED_FOR_FUNGIBLE_COMMON));
    }

    @HapiTest
    final Stream<DynamicTest> royaltyAndFractionalTogetherCaseStudy() {
        final var alice = "alice";
        final var amelie = AMELIE;
        final var usdcTreasury = "bank";
        final var usdcCollector = "usdcFees";
        final var westWindTreasury = COLLECTION;
        final var westWindArt = WEST_WIND_ART;
        final var usdc = "USDC";
        final var supplyKey = SUPPLY;

        final var txnFromTreasury = TXN_FROM_TREASURY;
        final var txnFromAmelie = "txnFromAmelie";

        return hapiTest(
                newKeyNamed(supplyKey),
                cryptoCreate(alice).balance(10 * ONE_HUNDRED_HBARS),
                cryptoCreate(amelie),
                cryptoCreate(usdcTreasury),
                cryptoCreate(usdcCollector),
                cryptoCreate(westWindTreasury),
                tokenCreate(usdc)
                        .signedBy(DEFAULT_PAYER, usdcTreasury, usdcCollector)
                        .initialSupply(Long.MAX_VALUE)
                        .withCustom(fractionalFee(1, 2, 0, OptionalLong.empty(), usdcCollector))
                        .treasury(usdcTreasury),
                tokenAssociate(westWindTreasury, usdc),
                tokenCreate(westWindArt)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0)
                        .supplyKey(supplyKey)
                        .treasury(westWindTreasury)
                        .withCustom(royaltyFeeWithFallback(
                                1, 100, fixedHtsFeeInheritingRoyaltyCollector(1, usdc), westWindTreasury)),
                tokenAssociate(amelie, List.of(westWindArt, usdc)),
                tokenAssociate(alice, List.of(westWindArt, usdc)),
                mintToken(westWindArt, List.of(copyFromUtf8(FUGUES_AND_FANTASTICS))),
                cryptoTransfer(moving(200, usdc).between(usdcTreasury, alice)).fee(ONE_HBAR),
                cryptoTransfer(movingUnique(westWindArt, 1L).between(westWindTreasury, amelie))
                        .fee(ONE_HBAR)
                        .via(txnFromTreasury),
                cryptoTransfer(
                                movingUnique(westWindArt, 1L).between(amelie, alice),
                                moving(200, usdc).between(alice, amelie),
                                movingHbar(10 * ONE_HUNDRED_HBARS)
                                        .between(alice, amelie)) // 100 USDC fractional fee,  royalty 2 USDC
                        .signedBy(amelie, alice)
                        .payingWith(amelie)
                        .via(txnFromAmelie)
                        .fee(ONE_HBAR),
                getTxnRecord(txnFromAmelie));
    }

    @HapiTest
    final Stream<DynamicTest> normalRoyaltyCaseStudy() {
        final var alice = "alice";
        final var amelie = AMELIE;
        final var usdcTreasury = "bank";
        final var westWindTreasury = COLLECTION;
        final var westWindArt = WEST_WIND_ART;
        final var usdc = "USDC";
        final var supplyKey = SUPPLY;

        final var txnFromTreasury = TXN_FROM_TREASURY;
        final var txnFromAmelie = "txnFromAmelie";

        return hapiTest(
                newKeyNamed(supplyKey),
                cryptoCreate(alice).balance(10 * ONE_HUNDRED_HBARS),
                cryptoCreate(amelie),
                cryptoCreate(usdcTreasury),
                cryptoCreate(westWindTreasury),
                tokenCreate(usdc).initialSupply(Long.MAX_VALUE).treasury(usdcTreasury),
                tokenAssociate(westWindTreasury, usdc),
                tokenCreate(westWindArt)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0)
                        .supplyKey(supplyKey)
                        .treasury(westWindTreasury)
                        .withCustom(royaltyFeeWithFallback(
                                1, 100, fixedHtsFeeInheritingRoyaltyCollector(1, usdc), westWindTreasury)),
                tokenAssociate(amelie, List.of(westWindArt, usdc)),
                tokenAssociate(alice, List.of(westWindArt, usdc)),
                mintToken(westWindArt, List.of(copyFromUtf8(FUGUES_AND_FANTASTICS))),
                cryptoTransfer(moving(200, usdc).between(usdcTreasury, alice)).fee(ONE_HBAR),
                cryptoTransfer(movingUnique(westWindArt, 1L).between(westWindTreasury, amelie))
                        .fee(ONE_HBAR)
                        .via(txnFromTreasury),
                cryptoTransfer(
                                movingUnique(westWindArt, 1L).between(amelie, alice),
                                moving(200, usdc).between(alice, amelie),
                                movingHbar(10 * ONE_HUNDRED_HBARS).between(alice, amelie))
                        .signedBy(amelie, alice)
                        .payingWith(amelie)
                        .via(txnFromAmelie)
                        .fee(ONE_HBAR),
                getTxnRecord(txnFromAmelie));
    }

    @HapiTest
    final Stream<DynamicTest> nestedHtsCaseStudy() {
        final var debbie = DEBBIE;
        final var edgar = EDGAR;
        final var feeToken = "FeeToken";
        final var tokenWithHtsFee = "TokenWithHtsFee";
        final var tokenWithNestedFee = TOKEN_WITH_NESTED_FEE;
        final var treasuryForTopLevelCollection = TOKEN_TREASURY;
        final var treasuryForNestedCollection = NESTED_TOKEN_TREASURY;

        final var txnFromTreasury = TXN_FROM_TREASURY;
        final var txnFromDebbie = "txnFromDebbie";

        return defaultHapiSpec("NestedHtsCaseStudy")
                .given(
                        cryptoCreate(debbie),
                        cryptoCreate(edgar),
                        cryptoCreate(treasuryForTopLevelCollection),
                        cryptoCreate(treasuryForNestedCollection),
                        tokenCreate(feeToken).treasury(DEFAULT_PAYER).initialSupply(Long.MAX_VALUE),
                        tokenAssociate(treasuryForNestedCollection, feeToken),
                        tokenCreate(tokenWithHtsFee)
                                .initialSupply(Long.MAX_VALUE)
                                .treasury(treasuryForNestedCollection)
                                .withCustom(fixedHtsFee(1L, feeToken, treasuryForNestedCollection)),
                        tokenAssociate(treasuryForTopLevelCollection, tokenWithHtsFee),
                        tokenCreate(tokenWithNestedFee)
                                .initialSupply(Long.MAX_VALUE)
                                .treasury(treasuryForTopLevelCollection)
                                .withCustom(fixedHtsFee(1L, tokenWithHtsFee, treasuryForTopLevelCollection)),
                        tokenAssociate(debbie, List.of(feeToken, tokenWithHtsFee, tokenWithNestedFee)),
                        tokenAssociate(edgar, tokenWithNestedFee),
                        cryptoTransfer(
                                        moving(1_000L, feeToken).between(DEFAULT_PAYER, debbie),
                                        moving(1_000L, tokenWithHtsFee).between(treasuryForNestedCollection, debbie),
                                        moving(1_000L, tokenWithNestedFee)
                                                .between(treasuryForTopLevelCollection, debbie))
                                .payingWith(treasuryForNestedCollection)
                                .fee(ONE_HBAR)
                                .via(txnFromTreasury),
                        getTxnRecord(txnFromTreasury).logged())
                .when(cryptoTransfer(moving(1L, tokenWithNestedFee).between(debbie, edgar))
                        .payingWith(debbie)
                        .fee(ONE_HBAR)
                        .via(txnFromDebbie))
                .then(
                        getTxnRecord(txnFromTreasury)
                                .hasTokenAmount(feeToken, debbie, 1_000L)
                                .hasTokenAmount(feeToken, DEFAULT_PAYER, -1_000L)
                                .hasTokenAmount(tokenWithHtsFee, debbie, 1_000L)
                                .hasTokenAmount(tokenWithHtsFee, treasuryForNestedCollection, -1_000L)
                                .hasTokenAmount(tokenWithNestedFee, debbie, 1_000L)
                                .hasTokenAmount(tokenWithNestedFee, treasuryForTopLevelCollection, -1_000L),
                        getTxnRecord(txnFromDebbie)
                                .hasTokenAmount(tokenWithNestedFee, edgar, 1L)
                                .hasTokenAmount(tokenWithNestedFee, debbie, -1L)
                                .hasAssessedCustomFee(tokenWithHtsFee, treasuryForTopLevelCollection, 1L)
                                .hasTokenAmount(tokenWithHtsFee, treasuryForTopLevelCollection, 1L)
                                .hasTokenAmount(tokenWithHtsFee, debbie, -1L)
                                .hasAssessedCustomFee(feeToken, treasuryForNestedCollection, 1L)
                                .hasTokenAmount(feeToken, treasuryForNestedCollection, 1L)
                                .hasTokenAmount(feeToken, debbie, -1L),
                        getAccountBalance(edgar).hasTokenBalance(tokenWithNestedFee, 1L),
                        getAccountBalance(debbie)
                                .hasTokenBalance(feeToken, 1_000L - 1L)
                                .hasTokenBalance(tokenWithHtsFee, 1_000L - 1L)
                                .hasTokenBalance(tokenWithNestedFee, 1_000L - 1L),
                        getAccountBalance(treasuryForTopLevelCollection)
                                .hasTokenBalance(tokenWithHtsFee, 1L)
                                .hasTokenBalance(tokenWithNestedFee, Long.MAX_VALUE - 1_000L),
                        getAccountBalance(treasuryForNestedCollection)
                                .hasTokenBalance(feeToken, 1L)
                                .hasTokenBalance(tokenWithHtsFee, Long.MAX_VALUE - 1_000L),
                        getAccountBalance(DEFAULT_PAYER).hasTokenBalance(feeToken, Long.MAX_VALUE - 1_000L));
    }

    @HapiTest
    final Stream<DynamicTest> canTransactInTokenWithSelfDenominatedFixedFee() {
        final var protocolToken = "protocolToken";
        final var gabriella = "gabriella";
        final var harry = "harry";
        final var nonExemptUnderfundedTxn = "nonExemptUnderfundedTxn";
        final var nonExemptFundedTxn = "nonExemptFundedTxn";

        return hapiTest(
                cryptoCreate(gabriella),
                cryptoCreate(harry),
                tokenCreate(protocolToken)
                        .blankMemo()
                        .name("Self-absorption")
                        .symbol("SELF")
                        .initialSupply(1_234_567L)
                        .treasury(gabriella)
                        .withCustom(fixedHtsFee(1, SENTINEL_ACCOUNT, gabriella)),
                tokenAssociate(harry, protocolToken),
                cryptoTransfer(moving(100, protocolToken).between(gabriella, harry)),
                cryptoTransfer(moving(100, protocolToken).between(harry, gabriella))
                        .via(nonExemptUnderfundedTxn)
                        .fee(ONE_HBAR)
                        .hasKnownStatus(INSUFFICIENT_SENDER_ACCOUNT_BALANCE_FOR_CUSTOM_FEE),
                getTxnRecord(nonExemptUnderfundedTxn)
                        .hasPriority(recordWith().tokenTransfers(changingNoFungibleBalances())),
                cryptoTransfer(moving(99, protocolToken).between(harry, gabriella))
                        .fee(ONE_HBAR)
                        .via(nonExemptFundedTxn),
                getTxnRecord(nonExemptFundedTxn)
                        .hasPriority(recordWith()
                                .tokenTransfers(changingFungibleBalances()
                                        .including(protocolToken, gabriella, +100L)
                                        .including(protocolToken, harry, -100L))));
    }

    /* ✅️ Should pass after fix for https://github.com/hashgraph/hedera-services/issues/1919
     *
     * SCENARIO:
     * ---------
     *   1. Create fungible "protocolToken" to use for a custom fee.
     *   2. Create non-fungible "artToken" with custom fee of 1 unit protocolToken.
     *   3. Use account "gabriella" as treasury for both tokens.
     *   4. Create account "harry" associated ONLY to artToken.
     *   5. Mint serial no 1 for art token, transfer to harry (no custom fee since gabriella is treasury and exempt).
     *   6. Transfer serial no 1 back to gabriella from harry.
     *   7. Transfer fails (correctly) with TOKEN_NOT_ASSOCIATED_TO_ACCOUNT, as harry isn't associated to protocolToken
     *   8. And following getTokenNftInfo query shows that harry is still the owner of serial no 1
     *   9. And following getAccountNftInfos query knows that harry still has serial no 1
     * */
    @HapiTest
    final Stream<DynamicTest> nftOwnersChangeAtomically() {
        final var artToken = "artToken";
        final var protocolToken = "protocolToken";
        final var gabriella = "gabriella";
        final var harry = "harry";
        final var uncompletableTxn = "uncompletableTxn";
        final var supplyKey = SUPPLY_KEY;
        final var serialNo1Meta = copyFromUtf8("PRICELESS");

        return defaultHapiSpec("NftOwnersChangeAtomically")
                .given(
                        newKeyNamed(supplyKey),
                        cryptoCreate(gabriella),
                        cryptoCreate(harry),
                        tokenCreate(protocolToken)
                                .blankMemo()
                                .name("Self-absorption")
                                .symbol("SELF")
                                .initialSupply(1_234_567L)
                                .treasury(gabriella),
                        tokenCreate(artToken)
                                .supplyKey(supplyKey)
                                .blankMemo()
                                .name("Splash")
                                .symbol("SPLSH")
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .initialSupply(0L)
                                .treasury(gabriella)
                                .withCustom(fixedHtsFee(1, protocolToken, gabriella)))
                .when(
                        mintToken(artToken, List.of(serialNo1Meta)),
                        tokenAssociate(harry, artToken),
                        cryptoTransfer(movingUnique(artToken, 1L).between(gabriella, harry)))
                .then(
                        cryptoTransfer(movingUnique(artToken, 1L).between(harry, gabriella))
                                .fee(ONE_HBAR)
                                .via(uncompletableTxn)
                                .hasKnownStatus(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT),
                        getTxnRecord(uncompletableTxn).hasPriority(recordWith().tokenTransfers(changingNoNftOwners())),
                        getTokenNftInfo(artToken, 1L).hasAccountID(harry));
    }

    @HapiTest
    final Stream<DynamicTest> treasuriesAreExemptFromAllCustomFees() {
        final var edgar = EDGAR;
        final var feeToken = "FeeToken";
        final var topLevelToken = "TopLevelToken";
        final var treasuryForTopLevel = "TokenTreasury";
        final var collectorForTopLevel = "FeeCollector";
        final var nonTreasury = "nonTreasury";

        final var txnFromTreasury = TXN_FROM_TREASURY;
        final var txnFromNonTreasury = "txnFromNonTreasury";

        return hapiTest(
                cryptoCreate(edgar),
                cryptoCreate(nonTreasury),
                cryptoCreate(TOKEN_TREASURY),
                cryptoCreate(treasuryForTopLevel),
                cryptoCreate(collectorForTopLevel).balance(0L),
                tokenCreate(feeToken).initialSupply(Long.MAX_VALUE).treasury(TOKEN_TREASURY),
                tokenAssociate(collectorForTopLevel, feeToken),
                tokenAssociate(treasuryForTopLevel, feeToken),
                tokenCreate(topLevelToken)
                        .initialSupply(Long.MAX_VALUE)
                        .treasury(treasuryForTopLevel)
                        .withCustom(fixedHbarFee(ONE_HBAR, collectorForTopLevel))
                        .withCustom(fixedHtsFee(50L, feeToken, collectorForTopLevel))
                        .withCustom(fractionalFee(1L, 10L, 5L, OptionalLong.of(50L), collectorForTopLevel))
                        .signedBy(DEFAULT_PAYER, treasuryForTopLevel, collectorForTopLevel),
                tokenAssociate(nonTreasury, List.of(topLevelToken, feeToken)),
                tokenAssociate(edgar, topLevelToken),
                cryptoTransfer(moving(2_000L, feeToken).distributing(TOKEN_TREASURY, treasuryForTopLevel, nonTreasury))
                        .payingWith(TOKEN_TREASURY)
                        .fee(ONE_HBAR),
                cryptoTransfer(moving(1_000L, topLevelToken).between(treasuryForTopLevel, nonTreasury))
                        .payingWith(treasuryForTopLevel)
                        .fee(ONE_HBAR)
                        .via(txnFromTreasury),
                getTxnRecord(txnFromTreasury)
                        .hasTokenAmount(topLevelToken, nonTreasury, 1_000L)
                        .hasTokenAmount(topLevelToken, treasuryForTopLevel, -1_000L)
                        .hasAssessedCustomFeesSize(0),
                getAccountBalance(collectorForTopLevel)
                        .hasTinyBars(0L)
                        .hasTokenBalance(feeToken, 0L)
                        .hasTokenBalance(topLevelToken, 0L),
                getAccountBalance(treasuryForTopLevel)
                        .hasTokenBalance(topLevelToken, Long.MAX_VALUE - 1_000L)
                        .hasTokenBalance(feeToken, 1_000L),
                getAccountBalance(nonTreasury)
                        .hasTokenBalance(topLevelToken, 1_000L)
                        .hasTokenBalance(feeToken, 1_000L),
                /* Now we perform the same transfer from a non-treasury and see all three fees charged */
                cryptoTransfer(moving(1_000L, topLevelToken).between(nonTreasury, edgar))
                        .payingWith(TOKEN_TREASURY)
                        .fee(ONE_HBAR)
                        .via(txnFromNonTreasury),
                getTxnRecord(txnFromNonTreasury)
                        .hasAssessedCustomFeesSize(3)
                        .hasTokenAmount(topLevelToken, edgar, 1_000L - 50L)
                        .hasTokenAmount(topLevelToken, nonTreasury, -1_000L)
                        .hasAssessedCustomFee(topLevelToken, collectorForTopLevel, 50L)
                        .hasTokenAmount(topLevelToken, collectorForTopLevel, 50L)
                        .hasAssessedCustomFee(HBAR_TOKEN_SENTINEL, collectorForTopLevel, ONE_HBAR)
                        .hasHbarAmount(collectorForTopLevel, ONE_HBAR)
                        .hasHbarAmount(nonTreasury, -ONE_HBAR)
                        .hasAssessedCustomFee(feeToken, collectorForTopLevel, 50L)
                        .hasTokenAmount(feeToken, collectorForTopLevel, 50L)
                        .hasTokenAmount(feeToken, nonTreasury, -50L),
                getAccountBalance(collectorForTopLevel)
                        .hasTinyBars(ONE_HBAR)
                        .hasTokenBalance(feeToken, 50L)
                        .hasTokenBalance(topLevelToken, 50L),
                getAccountBalance(edgar).hasTokenBalance(topLevelToken, 1_000L - 50L),
                getAccountBalance(nonTreasury)
                        .hasTokenBalance(topLevelToken, 0L)
                        .hasTokenBalance(feeToken, 1_000L - 50L));
    }

    @HapiTest
    final Stream<DynamicTest> collectorsAreExemptFromTheirOwnFeesButNotOthers() {
        final var edgar = EDGAR;
        final var topLevelToken = "TopLevelToken";
        final var treasuryForTopLevel = "TokenTreasury";
        final var firstCollectorForTopLevel = "AFeeCollector";
        final var secondCollectorForTopLevel = "BFeeCollector";

        final var txnFromCollector = "txnFromCollector";

        return hapiTest(
                cryptoCreate(edgar),
                cryptoCreate(TOKEN_TREASURY),
                cryptoCreate(treasuryForTopLevel),
                cryptoCreate(firstCollectorForTopLevel).balance(10 * ONE_HBAR),
                cryptoCreate(secondCollectorForTopLevel).balance(10 * ONE_HBAR),
                tokenCreate(topLevelToken)
                        .initialSupply(Long.MAX_VALUE)
                        .treasury(treasuryForTopLevel)
                        .withCustom(fixedHbarFee(ONE_HBAR, firstCollectorForTopLevel))
                        .withCustom(fixedHbarFee(2 * ONE_HBAR, secondCollectorForTopLevel))
                        .withCustom(fractionalFee(1L, 20L, 0L, OptionalLong.of(0L), firstCollectorForTopLevel))
                        .withCustom(fractionalFee(1L, 10L, 0L, OptionalLong.of(0L), secondCollectorForTopLevel))
                        .signedBy(
                                DEFAULT_PAYER,
                                treasuryForTopLevel,
                                firstCollectorForTopLevel,
                                secondCollectorForTopLevel),
                tokenAssociate(edgar, topLevelToken),
                cryptoTransfer(moving(2_000L, topLevelToken)
                        .distributing(treasuryForTopLevel, firstCollectorForTopLevel, secondCollectorForTopLevel)),
                getTokenInfo(topLevelToken).logged(),
                cryptoTransfer(moving(1_000L, topLevelToken).between(firstCollectorForTopLevel, edgar))
                        .payingWith(firstCollectorForTopLevel)
                        .fee(ONE_HBAR)
                        .via(txnFromCollector),
                getTxnRecord(txnFromCollector)
                        .hasAssessedCustomFeesSize(2)
                        .hasTokenAmount(topLevelToken, edgar, 1_000L - 100L)
                        .hasTokenAmount(topLevelToken, firstCollectorForTopLevel, -1_000L)
                        .hasAssessedCustomFee(topLevelToken, secondCollectorForTopLevel, 100L)
                        .hasTokenAmount(topLevelToken, secondCollectorForTopLevel, 100L)
                        .hasAssessedCustomFee(HBAR_TOKEN_SENTINEL, secondCollectorForTopLevel, 2 * ONE_HBAR)
                        .hasHbarAmount(secondCollectorForTopLevel, 2 * ONE_HBAR)
                        .logged(),
                getAccountBalance(firstCollectorForTopLevel).hasTokenBalance(topLevelToken, 0L),
                getAccountBalance(secondCollectorForTopLevel)
                        .hasTinyBars((10 + 2) * ONE_HBAR)
                        .hasTokenBalance(topLevelToken, 1_000L + 100L),
                getAccountBalance(edgar).hasTokenBalance(topLevelToken, 1_000L - 100L));
    }

    // HIP-573 tests below
    @HapiTest // HERE
    final Stream<DynamicTest> collectorIsChargedFixedFeeUnlessExempt() {
        return hapiTest(
                setupWellKnownTokenWithTwoFeesOnlyOneExemptingCollectors(NON_FUNGIBLE_UNIQUE, this::fixedFeeWith),
                getTokenInfo(TOKEN_WITH_PARALLEL_FEES).logged(),
                // This sender is only exempt from its own fee, but not from the other
                // fee; so a custom fee should be collected
                cryptoTransfer(movingUnique(TOKEN_WITH_PARALLEL_FEES, 1)
                                .between(COLLECTOR_OF_FEE_WITH_EXEMPTIONS, TOKEN_TREASURY))
                        .via(TXN_TRIGGERING_COLLECTOR_NON_EXEMPT_FEE),
                // This sender is already exempt from one fee, and the other
                // fee exempts all collectors; so no custom fees should be collected
                cryptoTransfer(movingUnique(TOKEN_WITH_PARALLEL_FEES, 2)
                                .between(COLLECTOR_OF_FEE_WITHOUT_EXEMPTIONS, TOKEN_TREASURY))
                        .via(TXN_TRIGGERING_COLLECTOR_EXEMPT_FEE),
                getTxnRecord(TXN_TRIGGERING_COLLECTOR_NON_EXEMPT_FEE)
                        .hasPriority(recordWith().assessedCustomFeeCount(1))
                        .logged(),
                getTxnRecord(TXN_TRIGGERING_COLLECTOR_EXEMPT_FEE)
                        .hasPriority(recordWith().assessedCustomFeeCount(0))
                        .logged());
    }

    @HapiTest // HERE
    final Stream<DynamicTest> collectorIsChargedFractionalFeeUnlessExempt() {
        return hapiTest(
                setupWellKnownTokenWithTwoFeesOnlyOneExemptingCollectors(FUNGIBLE_COMMON, this::fractionalFeeWith),
                cryptoCreate(CIVILIAN).maxAutomaticTokenAssociations(1),
                cryptoTransfer(moving(100_000, TOKEN_WITH_PARALLEL_FEES).between(TOKEN_TREASURY, CIVILIAN)),
                getTokenInfo(TOKEN_WITH_PARALLEL_FEES),
                // This receiver is only exempt from its own fee, so a custom
                // fee should be collected
                cryptoTransfer(moving(10_000, TOKEN_WITH_PARALLEL_FEES)
                                .between(CIVILIAN, COLLECTOR_OF_FEE_WITH_EXEMPTIONS))
                        .via(TXN_TRIGGERING_COLLECTOR_NON_EXEMPT_FEE),
                // This receiver is already exempt from its own fee, and the other
                // fee exempts all collectors; so no custom fees should be collected
                cryptoTransfer(moving(10_000, TOKEN_WITH_PARALLEL_FEES)
                                .between(CIVILIAN, COLLECTOR_OF_FEE_WITHOUT_EXEMPTIONS))
                        .via(TXN_TRIGGERING_COLLECTOR_EXEMPT_FEE),
                getTxnRecord(TXN_TRIGGERING_COLLECTOR_NON_EXEMPT_FEE)
                        .hasPriority(recordWith().assessedCustomFeeCount(1)),
                getTxnRecord(TXN_TRIGGERING_COLLECTOR_EXEMPT_FEE)
                        .hasPriority(recordWith().assessedCustomFeeCount(0)));
    }

    @HapiTest
    final Stream<DynamicTest> collectorIsChargedNetOfTransferFractionalFeeUnlessExempt() {
        return hapiTest(
                setupWellKnownTokenWithTwoFeesOnlyOneExemptingCollectors(
                        FUNGIBLE_COMMON, this::netOfTransferFractionalFeeWith),
                cryptoCreate(CIVILIAN).maxAutomaticTokenAssociations(1),
                getTokenInfo(TOKEN_WITH_PARALLEL_FEES),
                // This sender is only exempt from its own fee, so a custom
                // fee should be collected
                cryptoTransfer(moving(10_000, TOKEN_WITH_PARALLEL_FEES)
                                .between(COLLECTOR_OF_FEE_WITH_EXEMPTIONS, CIVILIAN))
                        .via(TXN_TRIGGERING_COLLECTOR_NON_EXEMPT_FEE),
                // This sender is already exempt from its own fee, and the other
                // fee exempts all collectors; so no custom fees should be collected
                cryptoTransfer(moving(10_000, TOKEN_WITH_PARALLEL_FEES)
                                .between(COLLECTOR_OF_FEE_WITHOUT_EXEMPTIONS, CIVILIAN))
                        .via(TXN_TRIGGERING_COLLECTOR_EXEMPT_FEE),
                getTxnRecord(TXN_TRIGGERING_COLLECTOR_NON_EXEMPT_FEE)
                        .hasPriority(recordWith().assessedCustomFeeCount(1)),
                getTxnRecord(TXN_TRIGGERING_COLLECTOR_EXEMPT_FEE)
                        .hasPriority(recordWith().assessedCustomFeeCount(0)));
    }

    @HapiTest // HERE custom fees fee collector ids in different order
    final Stream<DynamicTest> collectorIsChargedRoyaltyFeeUnlessExempt() {
        return hapiTest(
                setupWellKnownTokenWithTwoFeesOnlyOneExemptingCollectors(
                        NON_FUNGIBLE_UNIQUE, this::royaltyFeeNoFallbackWith),
                cryptoCreate(CIVILIAN).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(1),
                getTokenInfo(TOKEN_WITH_PARALLEL_FEES),
                // This sender is only exempt from its own fee, but not from the other
                // fee; so a custom fee should be collected
                cryptoTransfer(
                                movingUnique(TOKEN_WITH_PARALLEL_FEES, 1)
                                        .between(COLLECTOR_OF_FEE_WITH_EXEMPTIONS, CIVILIAN),
                                movingHbar(10 * ONE_HBAR).between(CIVILIAN, COLLECTOR_OF_FEE_WITH_EXEMPTIONS))
                        .via(TXN_TRIGGERING_COLLECTOR_NON_EXEMPT_FEE),
                // This sender is already exempt from one fee, and the other
                // fee exempts all collectors; so no custom fees should be collected
                cryptoTransfer(
                                movingUnique(TOKEN_WITH_PARALLEL_FEES, 2)
                                        .between(COLLECTOR_OF_FEE_WITHOUT_EXEMPTIONS, CIVILIAN),
                                movingHbar(10 * ONE_HBAR).between(CIVILIAN, COLLECTOR_OF_FEE_WITHOUT_EXEMPTIONS))
                        .via(TXN_TRIGGERING_COLLECTOR_EXEMPT_FEE),
                getTxnRecord(TXN_TRIGGERING_COLLECTOR_NON_EXEMPT_FEE)
                        .hasPriority(recordWith().assessedCustomFeeCount(1)),
                getTxnRecord(TXN_TRIGGERING_COLLECTOR_EXEMPT_FEE)
                        .hasPriority(recordWith().assessedCustomFeeCount(0)));
    }

    @HapiTest // HERE
    final Stream<DynamicTest> collectorIsChargedRoyaltyFallbackFeeUnlessExempt() {
        return hapiTest(
                setupWellKnownTokenWithTwoFeesOnlyOneExemptingCollectors(
                        NON_FUNGIBLE_UNIQUE, this::royaltyFeePlusFallbackWith),
                cryptoCreate(CIVILIAN).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(1),
                cryptoTransfer(movingUnique(TOKEN_WITH_PARALLEL_FEES, 3, 4).between(TOKEN_TREASURY, CIVILIAN)),
                getTokenInfo(TOKEN_WITH_PARALLEL_FEES),
                // This receiver is only exempt from its own fee, but not from the other
                // fee; so a custom fee should be collected
                cryptoTransfer(movingUnique(TOKEN_WITH_PARALLEL_FEES, 3)
                                .between(CIVILIAN, COLLECTOR_OF_FEE_WITH_EXEMPTIONS))
                        .via(TXN_TRIGGERING_COLLECTOR_NON_EXEMPT_FEE),
                // This sender is already exempt from one fee, and the other
                // fee exempts all collectors; so no custom fees should be collected
                cryptoTransfer(movingUnique(TOKEN_WITH_PARALLEL_FEES, 4)
                                .between(CIVILIAN, COLLECTOR_OF_FEE_WITHOUT_EXEMPTIONS))
                        .via(TXN_TRIGGERING_COLLECTOR_EXEMPT_FEE),
                getTxnRecord(TXN_TRIGGERING_COLLECTOR_NON_EXEMPT_FEE)
                        .hasPriority(recordWith().assessedCustomFeeCount(1))
                        .logged(),
                getTxnRecord(TXN_TRIGGERING_COLLECTOR_EXEMPT_FEE)
                        .hasPriority(recordWith().assessedCustomFeeCount(0))
                        .logged());
    }

    @HapiTest
    final Stream<DynamicTest> tokenFrozenOnTreasuryCannotBeFrozenAgain() {
        final var alice = "alice";
        final var token = "token";
        final var freezeKey = "freezeKey";
        return hapiTest(
                newKeyNamed(freezeKey),
                cryptoCreate(alice),
                tokenCreate(token)
                        .treasury(DEFAULT_PAYER)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(1000L)
                        .freezeKey(freezeKey)
                        .hasKnownStatus(SUCCESS),
                tokenAssociate(alice, token),
                tokenFreeze(token, alice).hasKnownStatus(SUCCESS),
                tokenFreeze(token, alice).hasKnownStatus(ACCOUNT_FROZEN_FOR_TOKEN));
    }

    @HapiTest
    final Stream<DynamicTest> onlyNonExemptCreditsAreConsideredForReclaimedFractionalUnits() {
        return hapiTest(
                cryptoCreate(FEE_COLLECTOR).balance(ONE_HUNDRED_HBARS),
                cryptoCreate("victim"),
                cryptoCreate(TOKEN_TREASURY),
                cryptoCreate("attacker").balance(ONE_MILLION_HBARS),
                tokenCreate("exploitToken")
                        .treasury(TOKEN_TREASURY)
                        .decimals(10)
                        .initialSupply(Long.MAX_VALUE)
                        .signedBy(DEFAULT_PAYER, TOKEN_TREASURY, FEE_COLLECTOR)
                        // This is large enough to be deducted from the victim
                        .withCustom(fractionalFee(1, 10, 0, OptionalLong.of(Long.MAX_VALUE), FEE_COLLECTOR))
                        // This is too small to be deducted from the victim (rounds down to 0)
                        .withCustom(fractionalFee(1, 100, 0, OptionalLong.of(Long.MAX_VALUE), FEE_COLLECTOR))
                        // A miscellaneous net-of-transfers fee, should not affect the outcome vis-a-vis the victim
                        .withCustom(
                                fractionalFeeNetOfTransfers(1, 10, 30, OptionalLong.of(Long.MAX_VALUE), FEE_COLLECTOR)),
                tokenAssociate("victim", "exploitToken"),
                tokenAssociate("attacker", "exploitToken"),
                cryptoTransfer(moving(1_000_000, "exploitToken").between(TOKEN_TREASURY, "attacker")),

                // Initially the receiver holds 10 tokens
                cryptoTransfer(moving(10L, "exploitToken").between(TOKEN_TREASURY, "victim")),
                tokenBalanceSnapshot("exploitToken", "beforeExploit", "victim"),
                cryptoTransfer(
                                moving(2000, "exploitToken").from("attacker"),
                                moving(1990, "exploitToken").to(TOKEN_TREASURY),
                                moving(10, "exploitToken").to("victim"))
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith("attacker")
                        .signedBy("attacker"),
                // The victim _should_ receive an additional 9 tokens, since floor(10 / 100) = 0, but (10 / 10) = 1
                // so the second fee should be applied to the victim while the first rounds down to nothing
                getAccountBalance("victim")
                        .hasTokenBalance("exploitToken", tokenChangeFromSnapshot("exploitToken", "beforeExploit", +9)));
    }

    @HapiTest
    final Stream<DynamicTest> splittingVerySmallDebitIntoMultipleDebitsCanRoundFeeDownToZero() {
        return hapiTest(
                cryptoCreate(FEE_COLLECTOR),
                inParallel(IntStream.range(0, 8)
                        .mapToObj(i -> cryptoCreate("sender" + i)
                                .balance(ONE_HUNDRED_HBARS * 1000)
                                .maxAutomaticTokenAssociations(1))
                        .toArray(SpecOperation[]::new)),
                cryptoCreate("nonExemptReceiver"),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate("token")
                        .treasury(TOKEN_TREASURY)
                        .decimals(10)
                        .initialSupply(Long.MAX_VALUE)
                        .signedBy(DEFAULT_PAYER, TOKEN_TREASURY, FEE_COLLECTOR)
                        .withCustom(fractionalFee(3, 20, 0, OptionalLong.of(Long.MAX_VALUE), FEE_COLLECTOR)),
                tokenAssociate("nonExemptReceiver", "token"),
                cryptoTransfer(moving(800, "token")
                        .distributing(
                                TOKEN_TREASURY,
                                IntStream.range(0, 8)
                                        .mapToObj(i -> "sender" + i)
                                        .toArray(String[]::new))),
                tokenBalanceSnapshot("token", "receiverBeforeTransfer", "nonExemptReceiver"),
                tokenBalanceSnapshot("token", "collectorBeforeTransfer", FEE_COLLECTOR),
                // Since the fee is 3/20, and the total non-exempt credits are 8, the total debit should be 1.2,
                // which rounds down to 1, so the fee collector should receive 1 token and the receiver 7 tokens
                cryptoTransfer(
                                moving(10, "token").from("sender0"),
                                moving(2, "token").to(TOKEN_TREASURY),
                                moving(8, "token").to("nonExemptReceiver"))
                        .fee(ONE_HUNDRED_HBARS)
                        .signedBy(DEFAULT_PAYER, "sender0"),
                getAccountBalance(FEE_COLLECTOR)
                        .hasTokenBalance("token", tokenChangeFromSnapshot("token", "collectorBeforeTransfer", +1)),
                getAccountBalance("nonExemptReceiver")
                        .hasTokenBalance("token", tokenChangeFromSnapshot("token", "receiverBeforeTransfer", +7)),
                tokenBalanceSnapshot("token", "receiverBeforeMultiwayTransfer", "nonExemptReceiver"),
                tokenBalanceSnapshot("token", "collectorBeforeMultiwayTransfer", FEE_COLLECTOR),
                // Since the fee is 3/20, but we only consider at most 3/10th of the non-exempt credits at time when
                // assessing the debits, there is never an amount of non-exempt credits large enough to round down
                // to a non-zero amount, and the fee collector gets nothing here
                cryptoTransfer(
                                // 10 debits, 3 from sender0, 1 from each of the other 7 senders
                                moving(3, "token").from("sender0"),
                                moving(1, "token").from("sender1"),
                                moving(1, "token").from("sender2"),
                                moving(1, "token").from("sender3"),
                                moving(1, "token").from("sender4"),
                                moving(1, "token").from("sender5"),
                                moving(1, "token").from("sender6"),
                                moving(1, "token").from("sender7"),
                                moving(2, "token").to(TOKEN_TREASURY),
                                moving(8, "token").to("nonExemptReceiver"))
                        .fee(ONE_HUNDRED_HBARS)
                        .via("roundedDownToZero")
                        .payingWith("sender0")
                        .signedBy(IntStream.range(0, 8)
                                .mapToObj(i -> "sender" + i)
                                .toArray(String[]::new)),
                getAccountBalance(FEE_COLLECTOR)
                        .hasTokenBalance(
                                "token", tokenChangeFromSnapshot("token", "collectorBeforeMultiwayTransfer", +0)),
                getAccountBalance("nonExemptReceiver")
                        .hasTokenBalance(
                                "token", tokenChangeFromSnapshot("token", "receiverBeforeMultiwayTransfer", +8)),
                getTxnRecord("roundedDownToZero").hasPriority(recordWith().assessedCustomFeeCount(0)));
    }

    @HapiTest
    final Stream<DynamicTest> minimumStillCollectedAfterSplittingVerySmallDebitIntoMultipleDebits() {
        return hapiTest(
                cryptoCreate(FEE_COLLECTOR),
                inParallel(IntStream.range(0, 8)
                        .mapToObj(i -> cryptoCreate("sender" + i)
                                .balance(ONE_HUNDRED_HBARS * 1000)
                                .maxAutomaticTokenAssociations(1))
                        .toArray(SpecOperation[]::new)),
                cryptoCreate("nonExemptReceiver"),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate("token")
                        .treasury(TOKEN_TREASURY)
                        .decimals(10)
                        .initialSupply(Long.MAX_VALUE)
                        .signedBy(DEFAULT_PAYER, TOKEN_TREASURY, FEE_COLLECTOR)
                        .withCustom(fractionalFee(3, 20, 8, OptionalLong.of(10), FEE_COLLECTOR)),
                tokenAssociate("nonExemptReceiver", "token"),
                cryptoTransfer(moving(8000, "token")
                        .distributing(
                                TOKEN_TREASURY,
                                IntStream.range(0, 8)
                                        .mapToObj(i -> "sender" + i)
                                        .toArray(String[]::new))),
                tokenBalanceSnapshot("token", "receiverBeforeMultiwayTransfer", "nonExemptReceiver"),
                tokenBalanceSnapshot("token", "collectorBeforeMultiwayTransfer", FEE_COLLECTOR),
                cryptoTransfer(
                                // 10 debits, 3 from sender0, 1 from each of the other 7 senders
                                moving(3, "token").from("sender0"),
                                moving(1, "token").from("sender1"),
                                moving(1, "token").from("sender2"),
                                moving(1, "token").from("sender3"),
                                moving(1, "token").from("sender4"),
                                moving(1, "token").from("sender5"),
                                moving(1, "token").from("sender6"),
                                moving(1, "token").from("sender7"),
                                moving(2, "token").to(TOKEN_TREASURY),
                                moving(8, "token").to("nonExemptReceiver"))
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith("sender0")
                        .signedBy(IntStream.range(0, 8)
                                .mapToObj(i -> "sender" + i)
                                .toArray(String[]::new)),
                getAccountBalance(FEE_COLLECTOR)
                        .hasTokenBalance(
                                "token", tokenChangeFromSnapshot("token", "collectorBeforeMultiwayTransfer", +2)),
                getAccountBalance("nonExemptReceiver")
                        .hasTokenBalance(
                                "token", tokenChangeFromSnapshot("token", "receiverBeforeMultiwayTransfer", +6)),
                tokenBalanceSnapshot("token", "receiverBeforeLargeTransfer", "nonExemptReceiver"),
                tokenBalanceSnapshot("token", "collectorBeforeLargeTransfer", FEE_COLLECTOR),
                cryptoTransfer(
                                // 100 debits, 30 from sender0, 10 from each of the other 7 senders
                                moving(30, "token").from("sender0"),
                                moving(10, "token").from("sender1"),
                                moving(10, "token").from("sender2"),
                                moving(10, "token").from("sender3"),
                                moving(10, "token").from("sender4"),
                                moving(10, "token").from("sender5"),
                                moving(10, "token").from("sender6"),
                                moving(10, "token").from("sender7"),
                                moving(20, "token").to(TOKEN_TREASURY),
                                moving(80, "token").to("nonExemptReceiver"))
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith("sender0")
                        .signedBy(IntStream.range(0, 8)
                                .mapToObj(i -> "sender" + i)
                                .toArray(String[]::new)),
                getAccountBalance(FEE_COLLECTOR)
                        .hasTokenBalance(
                                "token", tokenChangeFromSnapshot("token", "collectorBeforeLargeTransfer", +10)),
                getAccountBalance("nonExemptReceiver")
                        .hasTokenBalance(
                                "token", tokenChangeFromSnapshot("token", "receiverBeforeLargeTransfer", +70)));
    }

    @HapiTest
    final Stream<DynamicTest> nonNetOfTransfersAreAssessedRelativeToJustNonexemptDebits() {
        return hapiTest(
                cryptoCreate(FEE_COLLECTOR),
                cryptoCreate("firstSender"),
                cryptoCreate("nonExemptReceiver"),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate("token")
                        .treasury(TOKEN_TREASURY)
                        .decimals(10)
                        .initialSupply(Long.MAX_VALUE)
                        .signedBy(DEFAULT_PAYER, TOKEN_TREASURY, FEE_COLLECTOR)
                        // Three fractional fees, which should combine to redirect 30% of
                        // the total credits from a receiver to the fee collector, even
                        // when those credits are balanced by debits to multiple senders
                        .withCustom(fractionalFee(1, 10, 0, OptionalLong.of(Long.MAX_VALUE), FEE_COLLECTOR)),
                tokenAssociate("firstSender", "token"),
                tokenAssociate("nonExemptReceiver", "token"),
                cryptoTransfer(moving(3_000_000, "token").between(TOKEN_TREASURY, "firstSender")),
                tokenBalanceSnapshot("token", "receiverBefore", "nonExemptReceiver"),
                tokenBalanceSnapshot("token", "collectorBefore", FEE_COLLECTOR),
                cryptoTransfer(
                                moving(5000, "token").from("firstSender"),
                                moving(5000, "token").from(TOKEN_TREASURY),
                                moving(10000, "token").to("nonExemptReceiver"))
                        .fee(ONE_HUNDRED_HBARS)
                        .signedBy(DEFAULT_PAYER, "firstSender", TOKEN_TREASURY),
                getAccountBalance(FEE_COLLECTOR)
                        .hasTokenBalance("token", tokenChangeFromSnapshot("token", "collectorBefore", +1000)),
                getAccountBalance("nonExemptReceiver")
                        .hasTokenBalance("token", tokenChangeFromSnapshot("token", "receiverBefore", +9000)));
    }

    @HapiTest
    final Stream<DynamicTest> multipleNonNetOfTransfersAssessRelativeToDebits() {
        return hapiTest(
                cryptoCreate(FEE_COLLECTOR),
                cryptoCreate("firstSender"),
                cryptoCreate("secondSender"),
                cryptoCreate("nonExemptReceiver"),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate("tokenWithThreeFees")
                        .treasury(TOKEN_TREASURY)
                        .decimals(10)
                        .initialSupply(Long.MAX_VALUE)
                        .signedBy(DEFAULT_PAYER, TOKEN_TREASURY, FEE_COLLECTOR)
                        // Three fractional fees, which should combine to redirect 30% of
                        // the total credits from a receiver to the fee collector, even
                        // when those credits are balanced by debits to multiple senders
                        .withCustom(fractionalFee(1, 10, 0, OptionalLong.of(Long.MAX_VALUE), FEE_COLLECTOR))
                        .withCustom(fractionalFee(1, 10, 0, OptionalLong.of(Long.MAX_VALUE), FEE_COLLECTOR))
                        .withCustom(fractionalFee(1, 10, 0, OptionalLong.of(Long.MAX_VALUE), FEE_COLLECTOR)),
                tokenAssociate("firstSender", "tokenWithThreeFees"),
                tokenAssociate("secondSender", "tokenWithThreeFees"),
                tokenAssociate("nonExemptReceiver", "tokenWithThreeFees"),
                cryptoTransfer(moving(3_000_000, "tokenWithThreeFees")
                        .distributing(TOKEN_TREASURY, "firstSender", "secondSender", "nonExemptReceiver")),
                tokenBalanceSnapshot("tokenWithThreeFees", "receiverBeforeTransfer", "nonExemptReceiver"),
                tokenBalanceSnapshot("tokenWithThreeFees", "treasuryBeforeTransfer", TOKEN_TREASURY),
                cryptoTransfer(
                                moving(4000, "tokenWithThreeFees").from("firstSender"),
                                moving(1000, "tokenWithThreeFees").from("secondSender"),
                                moving(2000, "tokenWithThreeFees").to(TOKEN_TREASURY),
                                moving(3000, "tokenWithThreeFees").to("nonExemptReceiver"))
                        .fee(ONE_HUNDRED_HBARS)
                        .signedBy(DEFAULT_PAYER, "firstSender", "secondSender"),
                getAccountBalance(TOKEN_TREASURY)
                        .hasTokenBalance(
                                "tokenWithThreeFees",
                                tokenChangeFromSnapshot("tokenWithThreeFees", "treasuryBeforeTransfer", +2000)),
                getAccountBalance("nonExemptReceiver")
                        .hasTokenBalance(
                                "tokenWithThreeFees",
                                tokenChangeFromSnapshot("tokenWithThreeFees", "receiverBeforeTransfer", +2100)),
                tokenBalanceSnapshot("tokenWithThreeFees", "collectorBeforeTransfer", FEE_COLLECTOR),
                // But if every credit is exempt, no custom fees should be assessed or charged
                cryptoTransfer(
                                moving(4000, "tokenWithThreeFees").from("firstSender"),
                                moving(1000, "tokenWithThreeFees").from("secondSender"),
                                moving(2000, "tokenWithThreeFees").to(TOKEN_TREASURY),
                                moving(3000, "tokenWithThreeFees").to(FEE_COLLECTOR))
                        .fee(ONE_HUNDRED_HBARS)
                        .signedBy(DEFAULT_PAYER, "firstSender", "secondSender")
                        .via("noCustomFees"),
                getAccountBalance(FEE_COLLECTOR)
                        .hasTokenBalance(
                                "tokenWithThreeFees",
                                tokenChangeFromSnapshot("tokenWithThreeFees", "collectorBeforeTransfer", +3000)),
                getTxnRecord("noCustomFees").hasPriority(recordWith().assessedCustomFeeCount(0)));
    }

    @HapiTest
    final Stream<DynamicTest> originalWhitehatAssessChargeFractionalFeeMismatch() {
        final var htsCollector = "htsCollector";
        final var tokenReceiver = "tokenReceiver";
        final var tokenTreasury = "tokenTreasury";
        final var tokenOwner = "tokenOwner";
        final var token = "token";
        final long tokenTotal = 1000L;
        return hapiTest(
                cryptoCreate(htsCollector).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(tokenReceiver),
                cryptoCreate(tokenTreasury),
                cryptoCreate(tokenOwner).balance(ONE_MILLION_HBARS),
                tokenCreate(token)
                        .treasury(tokenTreasury)
                        .initialSupply(tokenTotal * 2 + 10)
                        .payingWith(htsCollector)
                        .withCustom(fractionalFee(1, 100, 0, OptionalLong.of(Long.MAX_VALUE), htsCollector)),
                tokenAssociate(tokenReceiver, token),
                tokenAssociate(tokenOwner, token),
                cryptoTransfer(moving(tokenTotal * 2, token).between(tokenTreasury, tokenOwner)),

                // Initially the receiver holds 10 tokens
                cryptoTransfer(moving(10L, token).between(tokenTreasury, tokenReceiver)),
                cryptoTransfer(
                                moving(2000, token).from(tokenOwner),
                                moving(1990, token).to(tokenTreasury),
                                moving(10, token).to(tokenReceiver) // credit to receiver
                                )
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(tokenOwner)
                        .signedBy(tokenOwner), // receiver sig not required

                // A 1/100 fraction of the non-exempt credits rounds down to zero, so the receiver still gets 10 units
                getAccountBalance(tokenReceiver).hasTokenBalance(token, 20L));
    }

    @HapiTest
    final Stream<DynamicTest> multipleMinimumFeeCannotReclaimMoreThanCredit() {
        return hapiTest(
                cryptoCreate(FEE_COLLECTOR).balance(ONE_HUNDRED_HBARS),
                cryptoCreate("receiver"),
                cryptoCreate(TOKEN_TREASURY),
                cryptoCreate("sender").balance(ONE_MILLION_HBARS),
                tokenCreate("token")
                        .treasury(TOKEN_TREASURY)
                        .initialSupply(Long.MAX_VALUE)
                        .payingWith(FEE_COLLECTOR)
                        .withCustom(fractionalFee(1, 100, 1, OptionalLong.of(Long.MAX_VALUE), FEE_COLLECTOR))
                        .withCustom(fractionalFee(1, 100, 2, OptionalLong.of(Long.MAX_VALUE), FEE_COLLECTOR))
                        .withCustom(fractionalFee(1, 100, 3, OptionalLong.of(Long.MAX_VALUE), FEE_COLLECTOR))
                        .withCustom(fractionalFee(1, 100, 4, OptionalLong.of(Long.MAX_VALUE), FEE_COLLECTOR)),
                tokenAssociate("receiver", "token"),
                tokenAssociate("sender", "token"),
                cryptoTransfer(moving(1_000_000, "token").between(TOKEN_TREASURY, "sender")),

                // Initially the receiver holds 10 tokens
                cryptoTransfer(moving(10L, "token").between(TOKEN_TREASURY, "receiver")),
                tokenBalanceSnapshot("token", "beforeTransfer", "receiver"),
                cryptoTransfer(
                                moving(40, "token").from("sender"),
                                moving(25, "token").to(TOKEN_TREASURY),
                                moving(6, "token").to(FEE_COLLECTOR),
                                moving(9, "token").to("receiver"))
                        .fee(ONE_HUNDRED_HBARS)
                        .via("zeroEffectiveCredit")
                        .payingWith("sender")
                        .hasKnownStatus(INSUFFICIENT_SENDER_ACCOUNT_BALANCE_FOR_CUSTOM_FEE),
                getAccountBalance("receiver")
                        .hasTokenBalance("token", tokenChangeFromSnapshot("token", "beforeTransfer", 0)));
    }

    private static final String TXN_TRIGGERING_COLLECTOR_EXEMPT_FEE = "collectorExempt";
    private static final String TXN_TRIGGERING_COLLECTOR_NON_EXEMPT_FEE = "collectorNonExempt";

    private static final String TWO_FEE_SUPPLY_KEY = "multiKey";
    private static final String TOKEN_WITH_PARALLEL_FEES = "twoFeeToken";
    private static final String COLLECTOR_OF_FEE_WITH_EXEMPTIONS = "selflessCollector";
    private static final String COLLECTOR_OF_FEE_WITHOUT_EXEMPTIONS = "selfishCollector";

    private HapiSpecOperation setupWellKnownTokenWithTwoFeesOnlyOneExemptingCollectors(
            final TokenType tokenType, final Function<Boolean, Function<HapiSpec, CustomFee>> feeFactory) {
        final var creationOp = tokenCreate(TOKEN_WITH_PARALLEL_FEES)
                .treasury(TOKEN_TREASURY)
                .supplyKey(TWO_FEE_SUPPLY_KEY)
                .tokenType(tokenType)
                .withCustom(feeFactory.apply(Boolean.TRUE))
                .withCustom(feeFactory.apply(Boolean.FALSE));
        final HapiSpecOperation finisher;
        if (tokenType == NON_FUNGIBLE_UNIQUE) {
            creationOp.initialSupply(0L);
            finisher = blockingOrder(
                    mintToken(
                            TOKEN_WITH_PARALLEL_FEES,
                            List.of(
                                    ByteString.copyFromUtf8("FIRST"),
                                    ByteString.copyFromUtf8("SECOND"),
                                    ByteString.copyFromUtf8("THIRD"),
                                    ByteString.copyFromUtf8("FOURTH"))),
                    cryptoTransfer(
                            movingUnique(TOKEN_WITH_PARALLEL_FEES, 1L)
                                    .between(TOKEN_TREASURY, COLLECTOR_OF_FEE_WITH_EXEMPTIONS),
                            movingUnique(TOKEN_WITH_PARALLEL_FEES, 2L)
                                    .between(TOKEN_TREASURY, COLLECTOR_OF_FEE_WITHOUT_EXEMPTIONS)));
        } else {
            creationOp.initialSupply(1_000_000L);
            finisher = cryptoTransfer(
                    moving(100_000L, TOKEN_WITH_PARALLEL_FEES)
                            .between(TOKEN_TREASURY, COLLECTOR_OF_FEE_WITH_EXEMPTIONS),
                    moving(100_000L, TOKEN_WITH_PARALLEL_FEES)
                            .between(TOKEN_TREASURY, COLLECTOR_OF_FEE_WITHOUT_EXEMPTIONS));
        }
        return blockingOrder(
                newKeyNamed(TWO_FEE_SUPPLY_KEY),
                cryptoCreate(TOKEN_TREASURY),
                cryptoCreate(COLLECTOR_OF_FEE_WITH_EXEMPTIONS)
                        .maxAutomaticTokenAssociations(2)
                        .key(DEFAULT_PAYER),
                cryptoCreate(COLLECTOR_OF_FEE_WITHOUT_EXEMPTIONS)
                        .maxAutomaticTokenAssociations(2)
                        .key(DEFAULT_PAYER),
                creationOp,
                finisher);
    }

    private Function<HapiSpec, CustomFee> fixedFeeWith(final boolean allCollectorsExempt) {
        return fixedHbarFee(ONE_HBAR, nameForCollectorOfFeeWith(allCollectorsExempt), allCollectorsExempt);
    }

    private Function<HapiSpec, CustomFee> fractionalFeeWith(final boolean allCollectorsExempt) {
        return fractionalFee(
                1, 10, 0, OptionalLong.empty(), nameForCollectorOfFeeWith(allCollectorsExempt), allCollectorsExempt);
    }

    private Function<HapiSpec, CustomFee> netOfTransferFractionalFeeWith(final boolean allCollectorsExempt) {
        return fractionalFeeNetOfTransfers(
                1, 10, 0, OptionalLong.empty(), nameForCollectorOfFeeWith(allCollectorsExempt), allCollectorsExempt);
    }

    private Function<HapiSpec, CustomFee> royaltyFeeNoFallbackWith(final boolean allCollectorsExempt) {
        return royaltyFeeNoFallback(1, 10, nameForCollectorOfFeeWith(allCollectorsExempt), allCollectorsExempt);
    }

    private Function<HapiSpec, CustomFee> royaltyFeePlusFallbackWith(final boolean allCollectorsExempt) {
        return royaltyFeeWithFallback(
                1,
                10,
                fixedHbarFeeInheritingRoyaltyCollector(ONE_HBAR),
                nameForCollectorOfFeeWith(allCollectorsExempt),
                allCollectorsExempt);
    }

    private String nameForCollectorOfFeeWith(final boolean allCollectorsExempt) {
        return allCollectorsExempt ? COLLECTOR_OF_FEE_WITH_EXEMPTIONS : COLLECTOR_OF_FEE_WITHOUT_EXEMPTIONS;
    }
}
