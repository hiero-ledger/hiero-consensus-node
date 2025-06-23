// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.token.batch;

import static com.google.protobuf.ByteString.copyFromUtf8;
import static com.hedera.services.bdd.junit.TestTags.TOKEN;
import static com.hedera.services.bdd.spec.HapiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.accountWith;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.changeFromSnapshot;
import static com.hedera.services.bdd.spec.assertions.AutoAssocAsserts.accountTokenPairs;
import static com.hedera.services.bdd.spec.assertions.NoNftTransfers.changingNoNftOwners;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenNftInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.queries.crypto.ExpectedTokenRel.relationshipWith;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createDefaultContract;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenDissociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenFreeze;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHbarFeeInheritingRoyaltyCollector;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHtsFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.royaltyFeeWithFallback;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.balanceSnapshot;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.FUNDING;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.TOKEN_TREASURY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_AMOUNT_TRANSFERS_ONLY_ALLOWED_FOR_FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_FROZEN_FOR_TOKEN;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_KYC_NOT_GRANTED_FOR_TOKEN;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_REPEATED_IN_ACCOUNT_AMOUNTS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_ACCOUNT_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TOKEN_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_AMOUNTS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_NFT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SENDER_DOES_NOT_OWN_NFT_SERIAL_NO;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_NOT_ASSOCIATED_TO_ACCOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_WAS_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSFERS_NOT_ZERO_SUM_FOR_TOKEN;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hederahashgraph.api.proto.java.TokenType;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

// This test cases are direct copies of TokenCreateSpecs. The difference here is that
// we are wrapping the operations in an atomic batch to confirm that everything works as expected.
@HapiTestLifecycle
@Tag(TOKEN)
public class AtomicTokenTransactSpecs {

    public static final String PAYER = "payer";
    private static final long TOTAL_SUPPLY = 1_000;
    private static final String A_TOKEN = "TokenA";
    private static final String B_TOKEN = "TokenB";
    private static final String FIRST_USER = "Client1";
    private static final String SECOND_USER = "Client2";
    public static final String CIVILIAN = "civilian";
    public static final String SIGNING_KEY_FIRST_USER = "signingKeyFirstUser";
    public static final String FIRST_TREASURY = "firstTreasury";
    public static final String BENEFICIARY = "beneficiary";
    public static final String MULTIPURPOSE = "multipurpose";
    public static final String SECOND_TREASURY = "secondTreasury";
    public static final String FREEZE_KEY = "freezeKey";
    public static final String MULTI_PURPOSE = "multiPurpose";
    public static final String SUPPLY_KEY = "supplyKey";
    public static final String SENTINEL_ACCOUNT = "0.0.0";
    public static final String SIGNING_KEY_TREASURY = "signingKeyTreasury";
    public static final String RANDOM_BENEFICIARY = "randomBeneficiary";
    public static final String SUPPLY = "supply";
    public static final String UNIQUE = "unique";
    public static final String FUNGIBLE = "fungible";
    public static final String TRANSFER_TXN = "transferTxn";
    private static final String BATCH_OPERATOR = "batchOperator";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(
                Map.of("atomicBatch.isEnabled", "true", "atomicBatch.maxNumberOfTransactions", "50"));
        testLifecycle.doAdhoc(cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS));
    }

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
                atomicBatch(
                                tokenCreate(fungibleToken)
                                        .freezeDefault(true)
                                        .freezeKey(multiPurpose)
                                        .tokenType(TokenType.FUNGIBLE_COMMON)
                                        .initialSupply(1_000L)
                                        .treasury(beneficiary)
                                        .batchKey(BATCH_OPERATOR),
                                tokenCreate(otherFungibleToken)
                                        .tokenType(FUNGIBLE_COMMON)
                                        .treasury(beneficiary)
                                        .batchKey(BATCH_OPERATOR),
                                tokenCreate(uniqueToken)
                                        .tokenType(NON_FUNGIBLE_UNIQUE)
                                        .supplyKey(multiPurpose)
                                        .initialSupply(0L)
                                        .treasury(TOKEN_TREASURY)
                                        .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                atomicBatch(mintToken(uniqueToken, List.of(copyFromUtf8("ONE"), copyFromUtf8("TWO")))
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getAccountInfo(beneficiary).savingSnapshot(beneficiary),
                getAccountInfo(TOKEN_TREASURY).savingSnapshot(TOKEN_TREASURY),
                atomicBatch(cryptoTransfer(
                                        movingUnique(uniqueToken, 1L).between(TOKEN_TREASURY, beneficiary),
                                        moving(500, fungibleToken).between(beneficiary, TOKEN_TREASURY))
                                .via(transferTxn)
                                .hasKnownStatus(ACCOUNT_FROZEN_FOR_TOKEN)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                getTxnRecord(transferTxn).hasPriority(recordWith().autoAssociated(accountTokenPairs(List.of()))),
                getAccountInfo(beneficiary)
                        .hasAlreadyUsedAutomaticAssociations(0)
                        .has(accountWith().noChangesFromSnapshot(beneficiary)),
                getAccountInfo(TOKEN_TREASURY)
                        .hasAlreadyUsedAutomaticAssociations(0)
                        .has(accountWith().noChangesFromSnapshot(TOKEN_TREASURY)),
                /* The treasury should still have an open auto-association slots */
                atomicBatch(cryptoTransfer(moving(500, otherFungibleToken).between(beneficiary, TOKEN_TREASURY))
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR));
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
                atomicBatch(
                                cryptoCreate(TOKEN_TREASURY)
                                        .maxAutomaticTokenAssociations(1)
                                        .batchKey(BATCH_OPERATOR),
                                cryptoCreate(beneficiary)
                                        .maxAutomaticTokenAssociations(2)
                                        .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                atomicBatch(
                                tokenCreate(fungibleToken)
                                        .kycKey(multiPurpose)
                                        .tokenType(TokenType.FUNGIBLE_COMMON)
                                        .initialSupply(1_000L)
                                        .treasury(beneficiary)
                                        .batchKey(BATCH_OPERATOR),
                                tokenCreate(otherFungibleToken)
                                        .tokenType(FUNGIBLE_COMMON)
                                        .treasury(beneficiary)
                                        .batchKey(BATCH_OPERATOR),
                                tokenCreate(uniqueToken)
                                        .tokenType(NON_FUNGIBLE_UNIQUE)
                                        .supplyKey(multiPurpose)
                                        .initialSupply(0L)
                                        .treasury(TOKEN_TREASURY)
                                        .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                atomicBatch(mintToken(uniqueToken, List.of(copyFromUtf8("ONE"), copyFromUtf8("TWO")))
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getAccountInfo(beneficiary).savingSnapshot(beneficiary),
                getAccountInfo(TOKEN_TREASURY).savingSnapshot(TOKEN_TREASURY),
                atomicBatch(cryptoTransfer(
                                        movingUnique(uniqueToken, 1L).between(TOKEN_TREASURY, beneficiary),
                                        moving(500, fungibleToken).between(beneficiary, TOKEN_TREASURY))
                                .via(transferTxn)
                                .hasKnownStatus(ACCOUNT_KYC_NOT_GRANTED_FOR_TOKEN)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                getTxnRecord(transferTxn).hasPriority(recordWith().autoAssociated(accountTokenPairs(List.of()))),
                getAccountInfo(beneficiary)
                        .hasAlreadyUsedAutomaticAssociations(0)
                        .has(accountWith().noChangesFromSnapshot(beneficiary)),
                getAccountInfo(TOKEN_TREASURY)
                        .hasAlreadyUsedAutomaticAssociations(0)
                        .has(accountWith().noChangesFromSnapshot(TOKEN_TREASURY)),
                /* The treasury should still have an open auto-association slots */
                atomicBatch(cryptoTransfer(moving(500, otherFungibleToken).between(beneficiary, TOKEN_TREASURY))
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR));
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
                atomicBatch(cryptoTransfer(
                                        movingUnique(uniqueToken, 1L).between(TOKEN_TREASURY, beneficiary),
                                        moving(500, fungibleToken).between(TOKEN_TREASURY, beneficiary),
                                        movingUnique(uniqueToken, 2L).between(TOKEN_TREASURY, unluckyBeneficiary))
                                .via(transferTxn)
                                .hasKnownStatus(SENDER_DOES_NOT_OWN_NFT_SERIAL_NO)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
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
                .when(atomicBatch(
                                mintToken(nonFungibleToken, List.of(copyFromUtf8("dark")))
                                        .batchKey(BATCH_OPERATOR),
                                tokenAssociate(theAccount, List.of(A_TOKEN, nonFungibleToken))
                                        .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR))
                .then(
                        atomicBatch(cryptoTransfer(movingUnique(A_TOKEN, 1).between(TOKEN_TREASURY, theAccount))
                                        .hasKnownStatus(INVALID_NFT_ID)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR)
                                .hasKnownStatus(INNER_TRANSACTION_FAILED),
                        atomicBatch(cryptoTransfer(moving(1, nonFungibleToken).between(TOKEN_TREASURY, theAccount))
                                        .hasKnownStatus(ACCOUNT_AMOUNT_TRANSFERS_ONLY_ALLOWED_FOR_FUNGIBLE_COMMON)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR)
                                .hasKnownStatus(INNER_TRANSACTION_FAILED));
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
                .when(atomicBatch(cryptoTransfer(
                                        moving(1, theCommonToken).between(TOKEN_TREASURY, theAccount),
                                        movingUnique(theUniqueToken, 1).between(TOKEN_TREASURY, theAccount))
                                .via(theTxn)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR))
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
                        atomicBatch(
                                        tokenDissociate(theContract, A_TOKEN).batchKey(BATCH_OPERATOR),
                                        tokenDissociate(theAccount, A_TOKEN).batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR),
                        getContractInfo(theContract).hasNoTokenRelationship(A_TOKEN),
                        getAccountInfo(theAccount).hasNoTokenRelationship(A_TOKEN))
                .then(
                        atomicBatch(cryptoTransfer(movingUnique(A_TOKEN, 1).between(TOKEN_TREASURY, theContract))
                                        .hasKnownStatus(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR)
                                .hasKnownStatus(INNER_TRANSACTION_FAILED),
                        atomicBatch(cryptoTransfer(movingUnique(A_TOKEN, 1).between(TOKEN_TREASURY, theAccount))
                                        .hasKnownStatus(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR)
                                .hasKnownStatus(INNER_TRANSACTION_FAILED),
                        atomicBatch(
                                        tokenAssociate(theContract, A_TOKEN).batchKey(BATCH_OPERATOR),
                                        tokenAssociate(theAccount, A_TOKEN).batchKey(BATCH_OPERATOR),
                                        cryptoTransfer(movingUnique(A_TOKEN, 1).between(TOKEN_TREASURY, theContract))
                                                .batchKey(BATCH_OPERATOR),
                                        cryptoTransfer(movingUnique(A_TOKEN, 2).between(TOKEN_TREASURY, theAccount))
                                                .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR),
                        getAccountBalance(theAccount).hasTokenBalance(A_TOKEN, 1),
                        getAccountBalance(theContract).hasTokenBalance(A_TOKEN, 1));
    }

    @HapiTest
    final Stream<DynamicTest> missingEntitiesRejected() {
        return defaultHapiSpec("missingEntitiesRejected")
                .given(tokenCreate("some").treasury(DEFAULT_PAYER))
                .when()
                .then(
                        atomicBatch(cryptoTransfer(moving(1L, "some").between(DEFAULT_PAYER, SENTINEL_ACCOUNT))
                                        .signedBy(DEFAULT_PAYER)
                                        .hasKnownStatus(INVALID_ACCOUNT_ID)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR)
                                .hasKnownStatus(INNER_TRANSACTION_FAILED),
                        atomicBatch(cryptoTransfer(moving(100_000_000_000_000L, SENTINEL_ACCOUNT)
                                                .between(DEFAULT_PAYER, FUNDING))
                                        .signedBy(DEFAULT_PAYER)
                                        .hasPrecheck(INVALID_TOKEN_ID)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR)
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
                        atomicBatch(cryptoTransfer(moving(100_000_000_000_000L, A_TOKEN)
                                                .between(FIRST_TREASURY, BENEFICIARY))
                                        .payingWith(PAYER)
                                        .signedBy(PAYER, FIRST_TREASURY)
                                        .fee(ONE_HUNDRED_HBARS)
                                        .hasKnownStatus(INSUFFICIENT_TOKEN_BALANCE)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR)
                                .hasKnownStatus(INNER_TRANSACTION_FAILED),
                        atomicBatch(cryptoTransfer(
                                                moving(1, A_TOKEN).between(FIRST_TREASURY, BENEFICIARY),
                                                movingHbar(ONE_HUNDRED_HBARS).between(FIRST_TREASURY, BENEFICIARY))
                                        .payingWith(PAYER)
                                        .signedBy(PAYER, FIRST_TREASURY)
                                        .fee(ONE_HUNDRED_HBARS)
                                        .hasKnownStatus(INSUFFICIENT_ACCOUNT_BALANCE)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR)
                                .hasKnownStatus(INNER_TRANSACTION_FAILED));
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
                        atomicBatch(cryptoTransfer(moving(100, A_TOKEN).between(TOKEN_TREASURY, RANDOM_BENEFICIARY))
                                        .hasKnownStatus(ACCOUNT_FROZEN_FOR_TOKEN)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR)
                                .hasKnownStatus(INNER_TRANSACTION_FAILED))
                .then();
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
                        atomicBatch(cryptoTransfer(
                                                moving(100, A_TOKEN).between(FIRST_TREASURY, BENEFICIARY),
                                                moving(10, B_TOKEN).between(SECOND_TREASURY, BENEFICIARY),
                                                movingHbar(1).between(BENEFICIARY, "tbd"))
                                        .fee(ONE_HUNDRED_HBARS)
                                        .hasKnownStatus(ACCOUNT_DELETED)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR)
                                .hasKnownStatus(INNER_TRANSACTION_FAILED))
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
                        atomicBatch(cryptoTransfer(
                                                moving(100, A_TOKEN).between(FIRST_TREASURY, BENEFICIARY),
                                                moving(100, B_TOKEN).between(SECOND_TREASURY, BENEFICIARY))
                                        .hasKnownStatus(INSUFFICIENT_TOKEN_BALANCE)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR)
                                .hasKnownStatus(INNER_TRANSACTION_FAILED))
                .then();
    }

    @HapiTest
    final Stream<DynamicTest> duplicateAccountsInTokenTransferRejected() {
        return defaultHapiSpec("DuplicateAccountsInTokenTransferRejected")
                .given(cryptoCreate(FIRST_TREASURY), cryptoCreate(FIRST_USER), cryptoCreate(SECOND_USER))
                .when(
                        tokenCreate(A_TOKEN).initialSupply(TOTAL_SUPPLY).treasury(FIRST_TREASURY),
                        tokenAssociate(FIRST_USER, A_TOKEN),
                        tokenAssociate(SECOND_USER, A_TOKEN))
                .then(atomicBatch(cryptoTransfer(
                                        moving(1, A_TOKEN).between(FIRST_TREASURY, FIRST_USER),
                                        moving(1, A_TOKEN).between(FIRST_TREASURY, SECOND_USER))
                                .dontFullyAggregateTokenTransfers()
                                .hasPrecheck(ACCOUNT_REPEATED_IN_ACCOUNT_AMOUNTS)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasPrecheck(ACCOUNT_REPEATED_IN_ACCOUNT_AMOUNTS));
    }

    @HapiTest
    final Stream<DynamicTest> nonZeroTransfersRejected() {
        return defaultHapiSpec("NonZeroTransfersRejected")
                .given(cryptoCreate(FIRST_TREASURY).balance(0L))
                .when(tokenCreate(A_TOKEN))
                .then(
                        atomicBatch(cryptoTransfer(moving(1, A_TOKEN).from(FIRST_TREASURY))
                                        .hasPrecheck(TRANSFERS_NOT_ZERO_SUM_FOR_TOKEN)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR)
                                .hasPrecheck(TRANSFERS_NOT_ZERO_SUM_FOR_TOKEN),
                        atomicBatch(cryptoTransfer(movingHbar(1).from(FIRST_TREASURY))
                                        .hasPrecheck(INVALID_ACCOUNT_AMOUNTS)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR)
                                .hasPrecheck(INVALID_ACCOUNT_AMOUNTS));
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
                .then(atomicBatch(cryptoTransfer(movingUnique(A_TOKEN, 1).between(TOKEN_TREASURY, FIRST_USER))
                                .hasKnownStatus(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
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
                atomicBatch(cryptoTransfer(movingUnique(A_TOKEN, 1).between(TOKEN_TREASURY, FIRST_USER))
                                .hasKnownStatus(ACCOUNT_FROZEN_FOR_TOKEN)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
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
                .then(atomicBatch(cryptoTransfer(movingUnique(A_TOKEN, 1).between(TOKEN_TREASURY, FIRST_USER))
                                .signedBy(DEFAULT_PAYER)
                                .hasKnownStatus(INVALID_SIGNATURE)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
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
                .then(atomicBatch(cryptoTransfer(movingUnique(A_TOKEN, 1).between(TOKEN_TREASURY, FIRST_USER))
                                .signedBy(SIGNING_KEY_TREASURY, DEFAULT_PAYER)
                                .hasKnownStatus(INVALID_SIGNATURE)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
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
                .when(atomicBatch(cryptoTransfer(
                                        movingUnique(A_TOKEN, 1).between(TOKEN_TREASURY, SECOND_USER),
                                        moving(101, B_TOKEN).between(TOKEN_TREASURY, FIRST_USER))
                                .hasKnownStatus(INSUFFICIENT_TOKEN_BALANCE)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED))
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
                .then(atomicBatch(cryptoTransfer(movingUnique(A_TOKEN, 1).between(TOKEN_TREASURY, FIRST_USER))
                                .signedBy(SIGNING_KEY_TREASURY, SIGNING_KEY_FIRST_USER, DEFAULT_PAYER)
                                .hasKnownStatus(TOKEN_WAS_DELETED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
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
                .then(atomicBatch(cryptoTransfer(moving(1, nonfungible).between(CIVILIAN, beneficiary))
                                .signedBy(DEFAULT_PAYER, CIVILIAN, beneficiary)
                                .fee(ONE_HBAR)
                                .hasKnownStatus(ACCOUNT_AMOUNT_TRANSFERS_ONLY_ALLOWED_FOR_FUNGIBLE_COMMON)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
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
                        atomicBatch(cryptoTransfer(movingUnique(artToken, 1L).between(harry, gabriella))
                                        .fee(ONE_HBAR)
                                        .via(uncompletableTxn)
                                        .hasKnownStatus(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR)
                                .hasKnownStatus(INNER_TRANSACTION_FAILED),
                        getTxnRecord(uncompletableTxn).hasPriority(recordWith().tokenTransfers(changingNoNftOwners())),
                        getTokenNftInfo(artToken, 1L).hasAccountID(harry));
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
                atomicBatch(tokenFreeze(token, alice)
                                .hasKnownStatus(ACCOUNT_FROZEN_FOR_TOKEN)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }
}
