// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.token.batch;

import static com.hedera.services.bdd.junit.TestTags.TOKEN;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.HapiSpecOperation.UnknownFieldLocation.OP_BODY;
import static com.hedera.services.bdd.spec.HapiSpecOperation.UnknownFieldLocation.SIGNED_TRANSACTION;
import static com.hedera.services.bdd.spec.HapiSpecOperation.UnknownFieldLocation.TRANSACTION;
import static com.hedera.services.bdd.spec.HapiSpecOperation.UnknownFieldLocation.TRANSACTION_BODY;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.accountWith;
import static com.hedera.services.bdd.spec.assertions.AutoAssocAsserts.accountTokenPairs;
import static com.hedera.services.bdd.spec.assertions.AutoAssocAsserts.accountTokenPairsInAnyOrder;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.keys.TrieSigMapGenerator.uniqueWithFullPrefixesFor;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.queries.crypto.ExpectedTokenRel.relationshipWith;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHbarFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHbarFeeInheritingRoyaltyCollector;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHtsFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHtsFeeInheritingRoyaltyCollector;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fractionalFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.incompleteCustomFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.royaltyFeeNoFallback;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.royaltyFeeWithFallback;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doSeveralWithStartupConfig;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doSeveralWithStartupConfigNow;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.specOps;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.THREE_MONTHS_IN_SECONDS;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hedera.services.bdd.suites.HapiSuite.salted;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_REPEATED_IN_ACCOUNT_AMOUNTS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CUSTOM_FEE_MUST_BE_POSITIVE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CUSTOM_FEE_NOT_FULLY_SPECIFIED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CUSTOM_ROYALTY_FEE_ONLY_ALLOWED_FOR_NON_FUNGIBLE_UNIQUE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.EMPTY_TOKEN_TRANSFER_ACCOUNT_AMOUNTS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.FRACTIONAL_FEE_MAX_AMOUNT_LESS_THAN_MIN_AMOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.FRACTION_DIVIDES_BY_ZERO;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_AUTORENEW_ACCOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_CUSTOM_FEE_COLLECTOR;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_EXPIRATION_TIME;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_RENEWAL_PERIOD;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_DECIMALS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_ID_IN_CUSTOM_FEES;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_INITIAL_SUPPLY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_MAX_SUPPLY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TREASURY_ACCOUNT_FOR_TOKEN;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ZERO_BYTE_IN_STRING;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MISSING_TOKEN_NAME;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MISSING_TOKEN_SYMBOL;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ROYALTY_FRACTION_CANNOT_EXCEED_ONE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_HAS_NO_FREEZE_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_HAS_NO_SUPPLY_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_NAME_TOO_LONG;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_NOT_ASSOCIATED_TO_FEE_COLLECTOR;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_SYMBOL_TOO_LONG;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSACTION_HAS_UNKNOWN_FIELDS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSFERS_NOT_ZERO_SUM_FOR_TOKEN;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;
import static java.lang.Integer.parseInt;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.HapiSpecSetup;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hederahashgraph.api.proto.java.TokenFreezeStatus;
import com.hederahashgraph.api.proto.java.TokenKycStatus;
import com.hederahashgraph.api.proto.java.TokenPauseStatus;
import com.hederahashgraph.api.proto.java.TokenSupplyType;
import com.hederahashgraph.api.proto.java.TokenType;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

// This test cases are direct copies of TokenCreateSpecs. The difference here is that
// we are wrapping the operations in an atomic batch to confirm that everything works as expected.
@HapiTestLifecycle
/**
 * Validates the {@code TokenCreate} transaction, including its:
 * <ul>
 *     <li>Auto-association behavior.</li>
 *     <li>Default values.</li>
 * </ul>
 */
@Tag(TOKEN)
public class AtomicTokenCreateSpecs {

    private static final String NON_FUNGIBLE_UNIQUE_FINITE = "non-fungible-unique-finite";
    private static final String PRIMARY = "primary";
    private static final String AUTO_RENEW_ACCOUNT = "autoRenewAccount";
    private static final String ADMIN_KEY = "adminKey";
    private static final String SUPPLY_KEY = "supplyKey";
    private static final String AUTO_RENEW = "autoRenew";
    private static final String CREATE_TXN = "createTxn";
    private static final String PAYER = "payer";
    public static final String INVALID_ACCOUNT = "999.999.999";

    private static final String TOKEN_TREASURY = "treasury";

    private static final String A_TOKEN = "TokenA";
    private static final String B_TOKEN = "TokenB";
    private static final String FIRST_USER = "Client1";
    private static final String SENTINEL_VALUE = "0.0.0";

    private static final long ONE_MONTH_IN_SECONDS = 2592000;
    private static final String BATCH_OPERATOR = "batchOperator";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(
                Map.of("atomicBatch.isEnabled", "true", "atomicBatch.maxNumberOfTransactions", "50"));
        testLifecycle.doAdhoc(cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS));
    }

    @HapiTest
    final Stream<DynamicTest> canHandleInvalidTokenCreateTransactions() {
        final String alice = "ALICE";
        return hapiTest(
                cryptoCreate(alice),
                atomicBatch(tokenCreate(null).hasKnownStatus(MISSING_TOKEN_NAME).batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(tokenCreate(A_TOKEN)
                                .treasury(alice)
                                .signedBy(DEFAULT_PAYER)
                                .hasKnownStatus(INVALID_SIGNATURE)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    /**
     * Validates that a {@code TokenCreate} auto-associates the following types of
     * accounts:
     * <ul>
     *     <li>Its treasury.</li>
     *     <li>Any fractional fee collector.</li>
     *     <li>Any self-denominated fixed fee collector.</li>
     * </ul>
     * It also verifies that these auto-associations don't "count" against the max
     * automatic associations limit defined by https://hips.hedera.com/hip/hip-23.
     */
    @HapiTest
    final Stream<DynamicTest> validateNewTokenAssociations() {
        final String notToBeToken = "notToBeToken";
        final String hbarCollector = "hbarCollector";
        final String fractionalCollector = "fractionalCollector";
        final String selfDenominatedFixedCollector = "selfDenominatedFixedCollector";
        final String otherSelfDenominatedFixedCollector = "otherSelfDenominatedFixedCollector";
        final String treasury = "treasury";
        final String tbd = "toBeDeletd";
        final String creationTxn = "creationTxn";
        final String failedCreationTxn = "failedCreationTxn";

        return hapiTest(
                cryptoCreate(tbd),
                cryptoDelete(tbd),
                cryptoCreate(hbarCollector),
                cryptoCreate(fractionalCollector),
                cryptoCreate(selfDenominatedFixedCollector),
                cryptoCreate(otherSelfDenominatedFixedCollector),
                cryptoCreate(treasury).maxAutomaticTokenAssociations(10).balance(ONE_HUNDRED_HBARS),
                getAccountInfo(treasury).savingSnapshot(treasury),
                getAccountInfo(hbarCollector).savingSnapshot(hbarCollector),
                getAccountInfo(fractionalCollector).savingSnapshot(fractionalCollector),
                getAccountInfo(selfDenominatedFixedCollector).savingSnapshot(selfDenominatedFixedCollector),
                getAccountInfo(otherSelfDenominatedFixedCollector).savingSnapshot(otherSelfDenominatedFixedCollector),
                atomicBatch(tokenCreate(A_TOKEN)
                                .tokenType(TokenType.FUNGIBLE_COMMON)
                                .initialSupply(Long.MAX_VALUE)
                                .treasury(treasury)
                                .withCustom(fixedHbarFee(20L, hbarCollector))
                                .withCustom(fractionalFee(1L, 100L, 1L, OptionalLong.of(5L), fractionalCollector))
                                .withCustom(fixedHtsFee(2L, SENTINEL_VALUE, selfDenominatedFixedCollector))
                                .withCustom(fixedHtsFee(3L, SENTINEL_VALUE, otherSelfDenominatedFixedCollector))
                                .signedBy(
                                        DEFAULT_PAYER,
                                        treasury,
                                        fractionalCollector,
                                        selfDenominatedFixedCollector,
                                        otherSelfDenominatedFixedCollector)
                                .via(creationTxn)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                atomicBatch(tokenCreate(notToBeToken)
                                .treasury(tbd)
                                .hasKnownStatus(INVALID_TREASURY_ACCOUNT_FOR_TOKEN)
                                .via(failedCreationTxn)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                /* Validate records */
                getTxnRecord(creationTxn)
                        .hasPriority(recordWith()
                                .autoAssociated(accountTokenPairs(List.of(
                                        Pair.of(fractionalCollector, A_TOKEN),
                                        Pair.of(selfDenominatedFixedCollector, A_TOKEN),
                                        Pair.of(otherSelfDenominatedFixedCollector, A_TOKEN),
                                        Pair.of(treasury, A_TOKEN))))),
                getTxnRecord(failedCreationTxn).hasPriority(recordWith().autoAssociated(accountTokenPairs(List.of()))),
                /* Validate state */
                getAccountInfo(hbarCollector).has(accountWith().noChangesFromSnapshot(hbarCollector)),
                getAccountInfo(treasury)
                        .hasMaxAutomaticAssociations(10)
                        /* TokenCreate auto-associations aren't part of the HIP-23 paradigm */
                        .hasAlreadyUsedAutomaticAssociations(0)
                        .has(accountWith().newAssociationsFromSnapshot(treasury, List.of(relationshipWith(A_TOKEN)))),
                getAccountInfo(fractionalCollector)
                        .has(accountWith()
                                .newAssociationsFromSnapshot(fractionalCollector, List.of(relationshipWith(A_TOKEN)))),
                getAccountInfo(selfDenominatedFixedCollector)
                        .has(accountWith()
                                .newAssociationsFromSnapshot(
                                        selfDenominatedFixedCollector, List.of(relationshipWith(A_TOKEN)))),
                getAccountInfo(otherSelfDenominatedFixedCollector)
                        .has(accountWith()
                                .newAssociationsFromSnapshot(
                                        otherSelfDenominatedFixedCollector, List.of(relationshipWith(A_TOKEN)))));
    }

    @HapiTest
    final Stream<DynamicTest> cannotCreateWithExcessiveLifetime() {
        return hapiTest(doSeveralWithStartupConfigNow("entities.maxLifetime", (value, now) -> {
            final var defaultMaxLifetime = Long.parseLong(value);
            final var excessiveExpiry = defaultMaxLifetime + now.getEpochSecond() + 12345L;
            return specOps(atomicBatch(tokenCreate("neverToBe")
                            .expiry(excessiveExpiry)
                            .hasKnownStatus(INVALID_EXPIRATION_TIME)
                            .batchKey(BATCH_OPERATOR))
                    .payingWith(BATCH_OPERATOR)
                    .hasKnownStatus(INNER_TRANSACTION_FAILED));
        }));
    }

    @HapiTest
    final Stream<DynamicTest> autoRenewValidationWorks() {
        final var deletingAccount = "deletingAccount";
        return hapiTest(
                cryptoCreate(AUTO_RENEW).balance(0L),
                cryptoCreate(deletingAccount).balance(0L),
                cryptoDelete(deletingAccount),
                atomicBatch(tokenCreate(PRIMARY)
                                .autoRenewAccount(deletingAccount)
                                .hasKnownStatus(INVALID_AUTORENEW_ACCOUNT)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(tokenCreate(PRIMARY)
                                .signedBy(GENESIS)
                                .autoRenewAccount(INVALID_ACCOUNT)
                                .hasKnownStatus(INVALID_AUTORENEW_ACCOUNT)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(tokenCreate(PRIMARY)
                                .autoRenewAccount(AUTO_RENEW)
                                .autoRenewPeriod(Long.MAX_VALUE)
                                .hasKnownStatus(INVALID_RENEWAL_PERIOD)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(tokenCreate(PRIMARY)
                                .signedBy(GENESIS)
                                .autoRenewAccount(AUTO_RENEW)
                                .hasKnownStatus(INVALID_SIGNATURE)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(tokenCreate(PRIMARY).autoRenewAccount(AUTO_RENEW).batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getTokenInfo(PRIMARY).logged());
    }

    @HapiTest
    final Stream<DynamicTest> creationYieldsExpectedToken() {
        return hapiTest(
                cryptoCreate(TOKEN_TREASURY).balance(0L),
                newKeyNamed("freeze"),
                atomicBatch(tokenCreate(PRIMARY)
                                .initialSupply(123)
                                .decimals(4)
                                .freezeDefault(true)
                                .freezeKey("freeze")
                                .treasury(TOKEN_TREASURY)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getTokenInfo(PRIMARY).logged().hasRegisteredId(PRIMARY));
    }

    @HapiTest
    final Stream<DynamicTest> creationHappyPath() {
        String memo = "JUMP";
        String saltedName = salted(PRIMARY);
        final var secondCreation = "secondCreation";
        final var pauseKey = "pauseKey";
        return hapiTest(
                cryptoCreate(TOKEN_TREASURY).balance(0L),
                cryptoCreate(AUTO_RENEW_ACCOUNT).balance(0L),
                newKeyNamed(ADMIN_KEY),
                newKeyNamed("freezeKey"),
                newKeyNamed("kycKey"),
                newKeyNamed(SUPPLY_KEY),
                newKeyNamed("wipeKey"),
                newKeyNamed("feeScheduleKey"),
                newKeyNamed(pauseKey),
                atomicBatch(
                                tokenCreate(PRIMARY)
                                        .supplyType(TokenSupplyType.FINITE)
                                        .entityMemo(memo)
                                        .name(saltedName)
                                        .treasury(TOKEN_TREASURY)
                                        .autoRenewAccount(AUTO_RENEW_ACCOUNT)
                                        .autoRenewPeriod(THREE_MONTHS_IN_SECONDS)
                                        .maxSupply(1000)
                                        .initialSupply(500)
                                        .decimals(1)
                                        .adminKey(ADMIN_KEY)
                                        .freezeKey("freezeKey")
                                        .kycKey("kycKey")
                                        .supplyKey(SUPPLY_KEY)
                                        .wipeKey("wipeKey")
                                        .feeScheduleKey("feeScheduleKey")
                                        .pauseKey(pauseKey)
                                        .via(CREATE_TXN)
                                        .batchKey(BATCH_OPERATOR),
                                tokenCreate(NON_FUNGIBLE_UNIQUE_FINITE)
                                        .tokenType(NON_FUNGIBLE_UNIQUE)
                                        .supplyType(TokenSupplyType.FINITE)
                                        .pauseKey(pauseKey)
                                        .initialSupply(0)
                                        .maxSupply(100)
                                        .treasury(TOKEN_TREASURY)
                                        .supplyKey(GENESIS)
                                        .via(secondCreation)
                                        .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getTxnRecord(secondCreation)
                        .logged()
                        .hasPriority(recordWith()
                                .autoAssociated(accountTokenPairsInAnyOrder(
                                        List.of(Pair.of(TOKEN_TREASURY, NON_FUNGIBLE_UNIQUE_FINITE))))),
                withOpContext((spec, opLog) -> {
                    var createTxn = getTxnRecord(CREATE_TXN);
                    allRunFor(spec, createTxn);
                    var timestamp = createTxn
                            .getResponseRecord()
                            .getConsensusTimestamp()
                            .getSeconds();
                    spec.registry().saveExpiry(PRIMARY, timestamp + THREE_MONTHS_IN_SECONDS);
                }),
                getTokenInfo(PRIMARY)
                        .logged()
                        .hasRegisteredId(PRIMARY)
                        .hasTokenType(TokenType.FUNGIBLE_COMMON)
                        .hasSupplyType(TokenSupplyType.FINITE)
                        .hasEntityMemo(memo)
                        .hasName(saltedName)
                        .hasTreasury(TOKEN_TREASURY)
                        .hasAutoRenewPeriod(THREE_MONTHS_IN_SECONDS)
                        .hasValidExpiry()
                        .hasDecimals(1)
                        .hasAdminKey(PRIMARY)
                        .hasFreezeKey(PRIMARY)
                        .hasKycKey(PRIMARY)
                        .hasSupplyKey(PRIMARY)
                        .hasWipeKey(PRIMARY)
                        .hasFeeScheduleKey(PRIMARY)
                        .hasPauseKey(PRIMARY)
                        .hasPauseStatus(TokenPauseStatus.Unpaused)
                        .hasMaxSupply(1000)
                        .hasTotalSupply(500)
                        .hasAutoRenewAccount(AUTO_RENEW_ACCOUNT),
                getTokenInfo(NON_FUNGIBLE_UNIQUE_FINITE)
                        .logged()
                        .hasRegisteredId(NON_FUNGIBLE_UNIQUE_FINITE)
                        .hasTokenType(NON_FUNGIBLE_UNIQUE)
                        .hasSupplyType(TokenSupplyType.FINITE)
                        .hasPauseKey(PRIMARY)
                        .hasPauseStatus(TokenPauseStatus.Unpaused)
                        .hasTotalSupply(0)
                        .hasMaxSupply(100),
                getAccountInfo(TOKEN_TREASURY)
                        .hasToken(relationshipWith(PRIMARY)
                                .balance(500)
                                .kyc(TokenKycStatus.Granted)
                                .freeze(TokenFreezeStatus.Unfrozen))
                        .hasToken(relationshipWith(NON_FUNGIBLE_UNIQUE_FINITE)
                                .balance(0)
                                .kyc(TokenKycStatus.KycNotApplicable)
                                .freeze(TokenFreezeStatus.FreezeNotApplicable)));
    }

    @HapiTest
    final Stream<DynamicTest> missingTreasurySignatureFails() {
        String memo = "JUMP";
        String saltedName = salted(PRIMARY);
        final var pauseKey = "pauseKey";
        return hapiTest(
                cryptoCreate(TOKEN_TREASURY).balance(0L),
                cryptoCreate(AUTO_RENEW_ACCOUNT).balance(0L),
                newKeyNamed(ADMIN_KEY),
                newKeyNamed("freezeKey"),
                newKeyNamed("kycKey"),
                newKeyNamed(SUPPLY_KEY),
                newKeyNamed("wipeKey"),
                newKeyNamed("feeScheduleKey"),
                newKeyNamed(pauseKey),
                atomicBatch(tokenCreate(PRIMARY)
                                .supplyType(TokenSupplyType.FINITE)
                                .entityMemo(memo)
                                .name(saltedName)
                                .treasury(TOKEN_TREASURY)
                                .autoRenewAccount(AUTO_RENEW_ACCOUNT)
                                .autoRenewPeriod(THREE_MONTHS_IN_SECONDS)
                                .maxSupply(1000)
                                .initialSupply(500)
                                .decimals(1)
                                .adminKey(ADMIN_KEY)
                                .freezeKey("freezeKey")
                                .kycKey("kycKey")
                                .supplyKey(SUPPLY_KEY)
                                .wipeKey("wipeKey")
                                .feeScheduleKey("feeScheduleKey")
                                .pauseKey(pauseKey)
                                .signedBy(DEFAULT_PAYER, ADMIN_KEY, AUTO_RENEW_ACCOUNT)
                                .sigMapPrefixes(uniqueWithFullPrefixesFor(DEFAULT_PAYER, ADMIN_KEY, AUTO_RENEW_ACCOUNT))
                                .hasKnownStatus(INVALID_SIGNATURE)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(tokenCreate(PRIMARY)
                                .supplyType(TokenSupplyType.FINITE)
                                .entityMemo(memo)
                                .name(saltedName)
                                .treasury(TOKEN_TREASURY)
                                .autoRenewAccount(AUTO_RENEW_ACCOUNT)
                                .autoRenewPeriod(THREE_MONTHS_IN_SECONDS)
                                .maxSupply(1000)
                                .initialSupply(500)
                                .decimals(1)
                                .adminKey(ADMIN_KEY)
                                .freezeKey("freezeKey")
                                .kycKey("kycKey")
                                .supplyKey(SUPPLY_KEY)
                                .wipeKey("wipeKey")
                                .feeScheduleKey("feeScheduleKey")
                                .pauseKey(pauseKey)
                                .signedBy(DEFAULT_PAYER, ADMIN_KEY, AUTO_RENEW_ACCOUNT, TOKEN_TREASURY)
                                .sigMapPrefixes(uniqueWithFullPrefixesFor(
                                        DEFAULT_PAYER, ADMIN_KEY, AUTO_RENEW_ACCOUNT, TOKEN_TREASURY))
                                .via(CREATE_TXN)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                withOpContext((spec, opLog) -> {
                    var createTxn = getTxnRecord(CREATE_TXN);
                    allRunFor(spec, createTxn);
                    var timestamp = createTxn
                            .getResponseRecord()
                            .getConsensusTimestamp()
                            .getSeconds();
                    spec.registry().saveExpiry(PRIMARY, timestamp + THREE_MONTHS_IN_SECONDS);
                }),
                getTokenInfo(PRIMARY)
                        .logged()
                        .hasRegisteredId(PRIMARY)
                        .hasTokenType(TokenType.FUNGIBLE_COMMON)
                        .hasSupplyType(TokenSupplyType.FINITE)
                        .hasEntityMemo(memo)
                        .hasName(saltedName)
                        .hasTreasury(TOKEN_TREASURY)
                        .hasAutoRenewPeriod(THREE_MONTHS_IN_SECONDS)
                        .hasValidExpiry()
                        .hasDecimals(1)
                        .hasAdminKey(PRIMARY)
                        .hasFreezeKey(PRIMARY)
                        .hasKycKey(PRIMARY)
                        .hasSupplyKey(PRIMARY)
                        .hasWipeKey(PRIMARY)
                        .hasFeeScheduleKey(PRIMARY)
                        .hasPauseKey(PRIMARY)
                        .hasPauseStatus(TokenPauseStatus.Unpaused)
                        .hasMaxSupply(1000)
                        .hasTotalSupply(500)
                        .hasAutoRenewAccount(AUTO_RENEW_ACCOUNT));
    }

    @HapiTest
    final Stream<DynamicTest> creationSetsCorrectExpiry() {
        return hapiTest(
                cryptoCreate(TOKEN_TREASURY).balance(0L),
                cryptoCreate(AUTO_RENEW).balance(0L),
                atomicBatch(tokenCreate(PRIMARY)
                                .autoRenewAccount(AUTO_RENEW)
                                .autoRenewPeriod(THREE_MONTHS_IN_SECONDS)
                                .treasury(TOKEN_TREASURY)
                                .via(CREATE_TXN)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                withOpContext((spec, opLog) -> {
                    var createTxn = getTxnRecord(CREATE_TXN);
                    allRunFor(spec, createTxn);
                    var timestamp = createTxn
                            .getResponseRecord()
                            .getConsensusTimestamp()
                            .getSeconds();
                    spec.registry().saveExpiry(PRIMARY, timestamp + THREE_MONTHS_IN_SECONDS);
                }),
                getTokenInfo(PRIMARY).logged().hasRegisteredId(PRIMARY).hasValidExpiry());
    }

    @HapiTest
    final Stream<DynamicTest> creationValidatesExpiry() {
        return hapiTest(atomicBatch(tokenCreate(PRIMARY)
                        .expiry(1000)
                        .hasKnownStatus(INVALID_EXPIRATION_TIME)
                        .batchKey(BATCH_OPERATOR))
                .payingWith(BATCH_OPERATOR)
                .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> creationValidatesFreezeDefaultWithNoFreezeKey() {
        return hapiTest(atomicBatch(tokenCreate(PRIMARY)
                        .freezeDefault(true)
                        .hasPrecheck(TOKEN_HAS_NO_FREEZE_KEY)
                        .batchKey(BATCH_OPERATOR))
                .payingWith(BATCH_OPERATOR)
                .hasPrecheck(TOKEN_HAS_NO_FREEZE_KEY));
    }

    @HapiTest
    final Stream<DynamicTest> creationValidatesMemo() {
        return hapiTest(atomicBatch(tokenCreate(PRIMARY)
                        .entityMemo("N\u0000!!!")
                        .hasKnownStatus(INVALID_ZERO_BYTE_IN_STRING)
                        .batchKey(BATCH_OPERATOR))
                .payingWith(BATCH_OPERATOR)
                .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> creationValidatesNonFungiblePrechecks() {
        return hapiTest(
                atomicBatch(tokenCreate(PRIMARY)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .initialSupply(0)
                                .decimals(0)
                                .hasPrecheck(TOKEN_HAS_NO_SUPPLY_KEY)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasPrecheck(TOKEN_HAS_NO_SUPPLY_KEY),
                atomicBatch(tokenCreate(PRIMARY)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .initialSupply(1)
                                .decimals(0)
                                .hasPrecheck(INVALID_TOKEN_INITIAL_SUPPLY)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasPrecheck(INVALID_TOKEN_INITIAL_SUPPLY),
                atomicBatch(tokenCreate(PRIMARY)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .initialSupply(0)
                                .decimals(1)
                                .hasPrecheck(INVALID_TOKEN_DECIMALS)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasPrecheck(INVALID_TOKEN_DECIMALS));
    }

    @HapiTest
    final Stream<DynamicTest> creationValidatesMaxSupply() {
        return hapiTest(
                atomicBatch(tokenCreate(PRIMARY)
                                .maxSupply(-1)
                                .hasPrecheck(INVALID_TOKEN_MAX_SUPPLY)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasPrecheck(INVALID_TOKEN_MAX_SUPPLY),
                atomicBatch(tokenCreate(PRIMARY)
                                .maxSupply(1)
                                .hasPrecheck(INVALID_TOKEN_MAX_SUPPLY)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasPrecheck(INVALID_TOKEN_MAX_SUPPLY),
                atomicBatch(tokenCreate(PRIMARY)
                                .supplyType(TokenSupplyType.FINITE)
                                .maxSupply(0)
                                .hasPrecheck(INVALID_TOKEN_MAX_SUPPLY)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasPrecheck(INVALID_TOKEN_MAX_SUPPLY),
                atomicBatch(tokenCreate(PRIMARY)
                                .supplyType(TokenSupplyType.FINITE)
                                .maxSupply(-1)
                                .hasPrecheck(INVALID_TOKEN_MAX_SUPPLY)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasPrecheck(INVALID_TOKEN_MAX_SUPPLY),
                atomicBatch(tokenCreate(PRIMARY)
                                .supplyType(TokenSupplyType.FINITE)
                                .initialSupply(2)
                                .maxSupply(1)
                                .hasPrecheck(INVALID_TOKEN_INITIAL_SUPPLY)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasPrecheck(INVALID_TOKEN_INITIAL_SUPPLY));
    }

    private HapiSpecOperation[] onlyValidCustomFeeScheduleBase() {
        return new HapiSpecOperation[] {
            newKeyNamed(customFeesKey),
            cryptoCreate(htsCollector),
            cryptoCreate(hbarCollector),
            cryptoCreate(tokenCollector),
            tokenCreate(feeDenom).treasury(htsCollector)
        };
    }

    @HapiTest
    final Stream<DynamicTest> customFeesDividesByZero() {
        return hapiTest(flattened(
                onlyValidCustomFeeScheduleBase(),
                atomicBatch(tokenCreate(token)
                                .treasury(tokenCollector)
                                .withCustom(fractionalFee(
                                        numerator,
                                        0,
                                        minimumToCollect,
                                        OptionalLong.of(maximumToCollect),
                                        tokenCollector))
                                .hasKnownStatus(FRACTION_DIVIDES_BY_ZERO)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)));
    }

    @HapiTest
    final Stream<DynamicTest> invalidCustomFeeCollector() {
        final String invalidEntityId = "1.2.786";
        return hapiTest(flattened(
                onlyValidCustomFeeScheduleBase(),
                atomicBatch(tokenCreate(token)
                                .treasury(tokenCollector)
                                .withCustom(fixedHbarFee(hbarAmount, invalidEntityId))
                                .hasKnownStatus(INVALID_CUSTOM_FEE_COLLECTOR)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)));
    }

    @HapiTest
    final Stream<DynamicTest> invalidTokenIdInCustomFees() {
        final String invalidEntityId = "1.2.786";
        return hapiTest(flattened(
                onlyValidCustomFeeScheduleBase(),
                atomicBatch(tokenCreate(token)
                                .treasury(tokenCollector)
                                .withCustom(fixedHtsFee(htsAmount, invalidEntityId, htsCollector))
                                .hasKnownStatus(INVALID_TOKEN_ID_IN_CUSTOM_FEES)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)));
    }

    @HapiTest
    final Stream<DynamicTest> tokenNotAssociatedToFeeCollector() {
        return hapiTest(flattened(
                onlyValidCustomFeeScheduleBase(),
                atomicBatch(tokenCreate(token)
                                .treasury(tokenCollector)
                                .withCustom(fixedHtsFee(htsAmount, feeDenom, hbarCollector))
                                .hasKnownStatus(TOKEN_NOT_ASSOCIATED_TO_FEE_COLLECTOR)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)));
    }

    @HapiTest
    final Stream<DynamicTest> customFeeNotFullySpecified() {
        return hapiTest(flattened(
                onlyValidCustomFeeScheduleBase(),
                atomicBatch(tokenCreate(token)
                                .treasury(tokenCollector)
                                .withCustom(incompleteCustomFee(hbarCollector))
                                .signedBy(DEFAULT_PAYER, tokenCollector, hbarCollector)
                                .hasKnownStatus(CUSTOM_FEE_NOT_FULLY_SPECIFIED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)));
    }

    @HapiTest
    final Stream<DynamicTest> customFeeMustBePositive() {
        final long negativeHtsFee = -100L;
        return hapiTest(flattened(
                onlyValidCustomFeeScheduleBase(),
                atomicBatch(tokenCreate(token)
                                .treasury(tokenCollector)
                                .withCustom(fixedHtsFee(negativeHtsFee, feeDenom, hbarCollector))
                                .hasKnownStatus(CUSTOM_FEE_MUST_BE_POSITIVE)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)));
    }

    @HapiTest
    final Stream<DynamicTest> customFeeDenominatorMustBePositive() {
        return hapiTest(flattened(
                onlyValidCustomFeeScheduleBase(),
                atomicBatch(tokenCreate(token)
                                .treasury(tokenCollector)
                                .withCustom(fractionalFee(
                                        numerator,
                                        -denominator,
                                        minimumToCollect,
                                        OptionalLong.of(maximumToCollect),
                                        tokenCollector))
                                .hasKnownStatus(CUSTOM_FEE_MUST_BE_POSITIVE)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)));
    }

    @HapiTest
    final Stream<DynamicTest> customFeeMinToCollectMustBePositive() {
        return hapiTest(flattened(
                onlyValidCustomFeeScheduleBase(),
                atomicBatch(tokenCreate(token)
                                .treasury(tokenCollector)
                                .withCustom(fractionalFee(
                                        numerator,
                                        denominator,
                                        -minimumToCollect,
                                        OptionalLong.of(maximumToCollect),
                                        tokenCollector))
                                .hasKnownStatus(CUSTOM_FEE_MUST_BE_POSITIVE)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)));
    }

    @HapiTest
    final Stream<DynamicTest> customFeeMinToCollectFractionalMustBePositive() {
        return hapiTest(flattened(
                onlyValidCustomFeeScheduleBase(),
                atomicBatch(tokenCreate(token)
                                .treasury(tokenCollector)
                                .withCustom(fractionalFee(
                                        numerator,
                                        denominator,
                                        minimumToCollect,
                                        OptionalLong.of(-maximumToCollect),
                                        tokenCollector))
                                .hasKnownStatus(CUSTOM_FEE_MUST_BE_POSITIVE)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)));
    }

    @HapiTest
    final Stream<DynamicTest> customFeeNumeratorAndDenominatorMustBePositive() {
        return hapiTest(flattened(
                onlyValidCustomFeeScheduleBase(),
                atomicBatch(tokenCreate(token)
                                .treasury(tokenCollector)
                                .withCustom(fractionalFee(
                                        -numerator,
                                        -denominator,
                                        minimumToCollect,
                                        OptionalLong.of(maximumToCollect),
                                        tokenCollector))
                                .hasKnownStatus(CUSTOM_FEE_MUST_BE_POSITIVE)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)));
    }

    @HapiTest
    final Stream<DynamicTest> fractionalFeeMaxAmountLessThenMinAmount() {
        return hapiTest(flattened(
                onlyValidCustomFeeScheduleBase(),
                atomicBatch(tokenCreate(token)
                                .treasury(tokenCollector)
                                .withCustom(fractionalFee(
                                        numerator,
                                        denominator,
                                        minimumToCollect,
                                        OptionalLong.of(minimumToCollect - 1),
                                        tokenCollector))
                                .hasKnownStatus(FRACTIONAL_FEE_MAX_AMOUNT_LESS_THAN_MIN_AMOUNT)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)));
    }

    @HapiTest
    final Stream<DynamicTest> customRoyaltyFeeOnlyAllowedForNFT() {
        return hapiTest(flattened(
                onlyValidCustomFeeScheduleBase(),
                atomicBatch(tokenCreate(token)
                                .treasury(tokenCollector)
                                .withCustom(royaltyFeeNoFallback(1, 2, tokenCollector))
                                .hasKnownStatus(CUSTOM_ROYALTY_FEE_ONLY_ALLOWED_FOR_NON_FUNGIBLE_UNIQUE)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)));
    }

    @HapiTest
    final Stream<DynamicTest> customRoyaltyNegativeNumerator() {
        return hapiTest(flattened(
                onlyValidCustomFeeScheduleBase(),
                atomicBatch(tokenCreate(token)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .supplyKey(GENESIS)
                                .initialSupply(0L)
                                .treasury(tokenCollector)
                                .withCustom(royaltyFeeNoFallback(-1, 2, tokenCollector))
                                .hasKnownStatus(CUSTOM_FEE_MUST_BE_POSITIVE)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)));
    }

    @HapiTest
    final Stream<DynamicTest> customRoyaltyNegativeDenominator() {
        return hapiTest(flattened(
                onlyValidCustomFeeScheduleBase(),
                atomicBatch(tokenCreate(token)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .supplyKey(GENESIS)
                                .initialSupply(0L)
                                .treasury(tokenCollector)
                                .withCustom(royaltyFeeNoFallback(1, -2, tokenCollector))
                                .hasKnownStatus(CUSTOM_FEE_MUST_BE_POSITIVE)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)));
    }

    @HapiTest
    final Stream<DynamicTest> royaltyFeeFractionDividesByZero() {
        return hapiTest(flattened(
                onlyValidCustomFeeScheduleBase(),
                atomicBatch(tokenCreate(token)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .supplyKey(GENESIS)
                                .initialSupply(0L)
                                .treasury(tokenCollector)
                                .withCustom(royaltyFeeNoFallback(1, 0, tokenCollector))
                                .hasKnownStatus(FRACTION_DIVIDES_BY_ZERO)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)));
    }

    @HapiTest
    final Stream<DynamicTest> royaltyFractionCannotExceedOne() {
        return hapiTest(flattened(
                onlyValidCustomFeeScheduleBase(),
                atomicBatch(tokenCreate(token)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .supplyKey(GENESIS)
                                .initialSupply(0L)
                                .treasury(tokenCollector)
                                .withCustom(royaltyFeeNoFallback(2, 1, tokenCollector))
                                .hasKnownStatus(ROYALTY_FRACTION_CANNOT_EXCEED_ONE)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)));
    }

    @HapiTest
    final Stream<DynamicTest> royaltyFeeWithFallbackNegativeFallbackFee() {
        return hapiTest(flattened(
                onlyValidCustomFeeScheduleBase(),
                atomicBatch(tokenCreate(token)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .supplyKey(GENESIS)
                                .initialSupply(0L)
                                .treasury(tokenCollector)
                                .withCustom(royaltyFeeWithFallback(
                                        1, 2, fixedHbarFeeInheritingRoyaltyCollector(-100), tokenCollector))
                                .hasKnownStatus(CUSTOM_FEE_MUST_BE_POSITIVE)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)));
    }

    @HapiTest
    final Stream<DynamicTest> royaltyCustomFeesInvalidTokenId() {
        return hapiTest(flattened(
                onlyValidCustomFeeScheduleBase(),
                atomicBatch(tokenCreate(token)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .supplyKey(GENESIS)
                                .initialSupply(0L)
                                .treasury(tokenCollector)
                                .withCustom(royaltyFeeWithFallback(
                                        1, 2, fixedHtsFeeInheritingRoyaltyCollector(100, "1.2.3"), tokenCollector))
                                .hasKnownStatus(INVALID_TOKEN_ID_IN_CUSTOM_FEES)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)));
    }

    @HapiTest
    final Stream<DynamicTest> feeCollectorSigningReqsWorkForTokenCreate() {
        return hapiTest(
                newKeyNamed(customFeesKey),
                cryptoCreate(htsCollector).receiverSigRequired(true),
                cryptoCreate(hbarCollector),
                cryptoCreate(tokenCollector),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(feeDenom).treasury(htsCollector),
                atomicBatch(tokenCreate(token)
                                .treasury(TOKEN_TREASURY)
                                .withCustom(fractionalFee(
                                        numerator,
                                        0,
                                        minimumToCollect,
                                        OptionalLong.of(maximumToCollect),
                                        tokenCollector))
                                .hasKnownStatus(INVALID_SIGNATURE)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(tokenCreate(token)
                                .treasury(TOKEN_TREASURY)
                                .withCustom(fixedHtsFee(htsAmount, feeDenom, htsCollector))
                                .hasKnownStatus(INVALID_SIGNATURE)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(tokenCreate(token)
                                .treasury(TOKEN_TREASURY)
                                .withCustom(fractionalFee(
                                        numerator,
                                        -denominator,
                                        minimumToCollect,
                                        OptionalLong.of(maximumToCollect),
                                        tokenCollector))
                                .hasKnownStatus(INVALID_SIGNATURE)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> creationValidatesName() {
        return hapiTest(
                cryptoCreate(TOKEN_TREASURY).balance(0L),
                tokenCreate(PRIMARY).name("").logged().hasPrecheck(MISSING_TOKEN_NAME),
                tokenCreate(PRIMARY).name("T\u0000ken").logged().hasPrecheck(INVALID_ZERO_BYTE_IN_STRING),
                doSeveralWithStartupConfig("tokens.maxTokenNameUtf8Bytes", value -> {
                    final var maxLen = parseInt(value);
                    return specOps(
                            atomicBatch(tokenCreate("tooLong")
                                            .name(TxnUtils.nAscii(maxLen + 1))
                                            .hasKnownStatus(TOKEN_NAME_TOO_LONG)
                                            .batchKey(BATCH_OPERATOR))
                                    .payingWith(BATCH_OPERATOR)
                                    .hasKnownStatus(INNER_TRANSACTION_FAILED),
                            atomicBatch(tokenCreate("tooLongAgain")
                                            .name(nCurrencySymbols(maxLen / 3 + 1))
                                            .hasKnownStatus(TOKEN_NAME_TOO_LONG)
                                            .batchKey(BATCH_OPERATOR))
                                    .payingWith(BATCH_OPERATOR)
                                    .hasKnownStatus(INNER_TRANSACTION_FAILED));
                }));
    }

    @HapiTest
    final Stream<DynamicTest> creationValidatesSymbol() {
        return hapiTest(
                cryptoCreate(TOKEN_TREASURY).balance(0L),
                tokenCreate("missingSymbol").symbol("").hasPrecheck(MISSING_TOKEN_SYMBOL),
                tokenCreate(PRIMARY).name("T\u0000ken").logged().hasPrecheck(INVALID_ZERO_BYTE_IN_STRING),
                doSeveralWithStartupConfig("tokens.maxSymbolUtf8Bytes", value -> {
                    final var maxLen = parseInt(value);
                    return specOps(
                            atomicBatch(tokenCreate("tooLong")
                                            .symbol(TxnUtils.nAscii(maxLen + 1))
                                            .hasKnownStatus(TOKEN_SYMBOL_TOO_LONG)
                                            .batchKey(BATCH_OPERATOR))
                                    .payingWith(BATCH_OPERATOR)
                                    .hasKnownStatus(INNER_TRANSACTION_FAILED),
                            atomicBatch(tokenCreate("tooLongAgain")
                                            .symbol(nCurrencySymbols(maxLen / 3 + 1))
                                            .hasKnownStatus(TOKEN_SYMBOL_TOO_LONG)
                                            .batchKey(BATCH_OPERATOR))
                                    .payingWith(BATCH_OPERATOR)
                                    .hasKnownStatus(INNER_TRANSACTION_FAILED));
                }));
    }

    private String nCurrencySymbols(int n) {
        return IntStream.range(0, n).mapToObj(ignore -> "€").collect(Collectors.joining());
    }

    @HapiTest
    final Stream<DynamicTest> creationRequiresAppropriateSigs() {
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY).balance(0L),
                newKeyNamed(ADMIN_KEY),
                atomicBatch(tokenCreate("shouldntWork")
                                .treasury(TOKEN_TREASURY)
                                .payingWith(PAYER)
                                .adminKey(ADMIN_KEY)
                                .signedBy(PAYER)
                                .hasKnownStatus(INVALID_SIGNATURE)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                /* treasury must sign */
                atomicBatch(tokenCreate("shouldntWorkEither")
                                .treasury(TOKEN_TREASURY)
                                .payingWith(PAYER)
                                .adminKey(ADMIN_KEY)
                                .signedBy(PAYER, ADMIN_KEY)
                                .sigMapPrefixes(uniqueWithFullPrefixesFor(PAYER, ADMIN_KEY))
                                .hasKnownStatus(INVALID_SIGNATURE)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> creationValidatesTreasuryAccount() {
        return hapiTest(
                cryptoCreate(TOKEN_TREASURY).balance(0L),
                cryptoDelete(TOKEN_TREASURY),
                atomicBatch(tokenCreate("shouldntWork")
                                .treasury(TOKEN_TREASURY)
                                .hasKnownStatus(INVALID_TREASURY_ACCOUNT_FOR_TOKEN)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> initialSupplyMustBeSane() {
        return hapiTest(
                atomicBatch(tokenCreate("sinking")
                                .initialSupply(-1L)
                                .hasPrecheck(INVALID_TOKEN_INITIAL_SUPPLY)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasPrecheck(INVALID_TOKEN_INITIAL_SUPPLY),
                atomicBatch(tokenCreate("bad decimals")
                                .decimals(-1)
                                .hasPrecheck(INVALID_TOKEN_DECIMALS)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasPrecheck(INVALID_TOKEN_DECIMALS),
                atomicBatch(tokenCreate("bad decimals")
                                .decimals(1 << 31)
                                .hasPrecheck(INVALID_TOKEN_DECIMALS)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasPrecheck(INVALID_TOKEN_DECIMALS),
                atomicBatch(tokenCreate("bad initial supply")
                                .initialSupply(1L << 63)
                                .hasPrecheck(INVALID_TOKEN_INITIAL_SUPPLY)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasPrecheck(INVALID_TOKEN_INITIAL_SUPPLY));
    }

    @HapiTest
    final Stream<DynamicTest> treasuryHasCorrectBalance() {
        String token = salted("myToken");

        int decimals = 1;
        long initialSupply = 100_000;

        return hapiTest(
                cryptoCreate(TOKEN_TREASURY).balance(1L),
                atomicBatch(tokenCreate(token)
                                .treasury(TOKEN_TREASURY)
                                .decimals(decimals)
                                .initialSupply(initialSupply)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getAccountBalance(TOKEN_TREASURY).hasTinyBars(1L).hasTokenBalance(token, initialSupply));
    }

    private HapiSpecOperation[] prechecksWorkBase() {
        return new HapiSpecOperation[] {
            cryptoCreate(TOKEN_TREASURY).withUnknownFieldIn(TRANSACTION).hasPrecheck(TRANSACTION_HAS_UNKNOWN_FIELDS),
            cryptoCreate(TOKEN_TREASURY)
                    .withUnknownFieldIn(TRANSACTION_BODY)
                    .withProtoStructure(HapiSpecSetup.TxnProtoStructure.NEW)
                    .hasPrecheck(TRANSACTION_HAS_UNKNOWN_FIELDS),
            cryptoCreate(TOKEN_TREASURY)
                    .withUnknownFieldIn(SIGNED_TRANSACTION)
                    .withProtoStructure(HapiSpecSetup.TxnProtoStructure.NEW)
                    .hasPrecheck(TRANSACTION_HAS_UNKNOWN_FIELDS),
            cryptoCreate(TOKEN_TREASURY).withUnknownFieldIn(OP_BODY).hasPrecheck(TRANSACTION_HAS_UNKNOWN_FIELDS),
            cryptoCreate(TOKEN_TREASURY).balance(0L),
            cryptoCreate(FIRST_USER).balance(0L),
            tokenCreate(A_TOKEN).initialSupply(100).treasury(TOKEN_TREASURY),
            tokenCreate(B_TOKEN).initialSupply(100).treasury(TOKEN_TREASURY)
        };
    }

    @HapiTest
    final Stream<DynamicTest> prechecksWork() {
        return hapiTest(flattened(
                prechecksWorkBase(),
                atomicBatch(cryptoTransfer(
                                        moving(1, A_TOKEN).between(TOKEN_TREASURY, FIRST_USER),
                                        moving(1, A_TOKEN).between(TOKEN_TREASURY, FIRST_USER))
                                .dontFullyAggregateTokenTransfers()
                                .hasPrecheck(ACCOUNT_REPEATED_IN_ACCOUNT_AMOUNTS)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasPrecheck(ACCOUNT_REPEATED_IN_ACCOUNT_AMOUNTS),
                atomicBatch(cryptoTransfer(
                                        movingHbar(1).between(TOKEN_TREASURY, FIRST_USER),
                                        movingHbar(1).between(TOKEN_TREASURY, FIRST_USER))
                                .dontFullyAggregateTokenTransfers()
                                .hasPrecheck(ACCOUNT_REPEATED_IN_ACCOUNT_AMOUNTS)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasPrecheck(ACCOUNT_REPEATED_IN_ACCOUNT_AMOUNTS),
                atomicBatch(cryptoTransfer(
                                        moving(1, A_TOKEN).between(TOKEN_TREASURY, FIRST_USER),
                                        moving(1, A_TOKEN).between(TOKEN_TREASURY, FIRST_USER))
                                .dontFullyAggregateTokenTransfers()
                                .hasPrecheck(ACCOUNT_REPEATED_IN_ACCOUNT_AMOUNTS)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasPrecheck(ACCOUNT_REPEATED_IN_ACCOUNT_AMOUNTS),
                atomicBatch(tokenAssociate(FIRST_USER, A_TOKEN).batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                atomicBatch(cryptoTransfer(moving(0, A_TOKEN).between(TOKEN_TREASURY, FIRST_USER))
                                .hasPrecheck(OK)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                atomicBatch(cryptoTransfer(moving(10, A_TOKEN).from(TOKEN_TREASURY))
                                .hasPrecheck(TRANSFERS_NOT_ZERO_SUM_FOR_TOKEN)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasPrecheck(TRANSFERS_NOT_ZERO_SUM_FOR_TOKEN),
                atomicBatch(cryptoTransfer(moving(10, A_TOKEN).empty())
                                .hasPrecheck(EMPTY_TOKEN_TRANSFER_ACCOUNT_AMOUNTS)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasPrecheck(EMPTY_TOKEN_TRANSFER_ACCOUNT_AMOUNTS)));
    }

    @HapiTest
    final Stream<DynamicTest> deletedAccountCannotBeFeeCollector() {
        final var account = "account";
        return hapiTest(
                cryptoCreate(account),
                cryptoDelete(account),
                atomicBatch(tokenCreate("anyToken")
                                .treasury(DEFAULT_PAYER)
                                .tokenType(FUNGIBLE_COMMON)
                                .initialSupply(1000L)
                                .withCustom(fixedHbarFee(1, account))
                                .hasKnownStatus(INVALID_CUSTOM_FEE_COLLECTOR)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> autoRenewLessThenAMonth() {
        return hapiTest(
                cryptoCreate(AUTO_RENEW_ACCOUNT).balance(0L),
                atomicBatch(tokenCreate("token")
                                .autoRenewAccount(AUTO_RENEW_ACCOUNT)
                                .autoRenewPeriod(ONE_MONTH_IN_SECONDS - 1)
                                .hasKnownStatus(INVALID_RENEWAL_PERIOD)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> withLongMinNumeratorRoyaltyFeeWithFallback() {
        return hapiTest(
                newKeyNamed("supplyKey"),
                cryptoCreate(TOKEN_TREASURY),
                cryptoCreate("autoRenewAccount"),
                cryptoCreate("feeCollector"),
                tokenCreate("feeToken"),
                tokenAssociate("feeCollector", "feeToken"),
                atomicBatch(tokenCreate("nonFungibleToken")
                                .treasury(TOKEN_TREASURY)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .initialSupply(0L)
                                .supplyKey("supplyKey")
                                .autoRenewAccount("autoRenewAccount")
                                .withCustom(royaltyFeeWithFallback(
                                        Long.MIN_VALUE,
                                        10L,
                                        fixedHbarFeeInheritingRoyaltyCollector(123L),
                                        "feeCollector"))
                                .hasKnownStatus(CUSTOM_FEE_MUST_BE_POSITIVE)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> withLongMinDenominatorRoyaltyFeeWithFallback() {
        return hapiTest(
                newKeyNamed("supplyKey"),
                cryptoCreate(TOKEN_TREASURY),
                cryptoCreate("autoRenewAccount"),
                cryptoCreate("feeCollector"),
                tokenCreate("feeToken"),
                tokenAssociate("feeCollector", "feeToken"),
                atomicBatch(tokenCreate("nonFungibleToken")
                                .treasury(TOKEN_TREASURY)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .initialSupply(0L)
                                .supplyKey("supplyKey")
                                .autoRenewAccount("autoRenewAccount")
                                .withCustom(royaltyFeeWithFallback(
                                        1,
                                        Long.MIN_VALUE,
                                        fixedHbarFeeInheritingRoyaltyCollector(123L),
                                        "feeCollector"))
                                .hasKnownStatus(CUSTOM_FEE_MUST_BE_POSITIVE)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> withLongMinNumeratorRoyaltyFeeNoFallback() {
        return hapiTest(
                cryptoCreate(TOKEN_TREASURY),
                cryptoCreate("feeCollector"),
                atomicBatch(tokenCreate(token)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .supplyKey(GENESIS)
                                .initialSupply(0L)
                                .treasury(TOKEN_TREASURY)
                                .withCustom(royaltyFeeNoFallback(Long.MIN_VALUE, 2, "feeCollector"))
                                .hasKnownStatus(CUSTOM_FEE_MUST_BE_POSITIVE)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> withLongMinDenominatorRoyaltyFeeNoFallback() {
        return hapiTest(
                cryptoCreate(TOKEN_TREASURY),
                cryptoCreate("feeCollector"),
                atomicBatch(tokenCreate(token)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .supplyKey(GENESIS)
                                .initialSupply(0L)
                                .treasury(TOKEN_TREASURY)
                                .withCustom(royaltyFeeNoFallback(1, Long.MIN_VALUE, "feeCollector"))
                                .hasKnownStatus(CUSTOM_FEE_MUST_BE_POSITIVE)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> withLongMinAutoRenewPeriod() {
        return hapiTest(
                cryptoCreate(TOKEN_TREASURY),
                cryptoCreate("autoRenewAccount"),
                atomicBatch(tokenCreate(token)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .supplyKey(GENESIS)
                                .initialSupply(0L)
                                .treasury(TOKEN_TREASURY)
                                .autoRenewAccount("autoRenewAccount")
                                .autoRenewPeriod(Long.MIN_VALUE)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR));
    }

    @HapiTest
    final Stream<DynamicTest> withNegativeMinAutoRenewPeriod() {
        return hapiTest(
                cryptoCreate(TOKEN_TREASURY),
                cryptoCreate("autoRenewAccount"),
                atomicBatch(tokenCreate(token)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .supplyKey(GENESIS)
                                .initialSupply(0L)
                                .treasury(TOKEN_TREASURY)
                                .autoRenewAccount("autoRenewAccount")
                                .autoRenewPeriod(-1)
                                .hasKnownStatus(INVALID_RENEWAL_PERIOD)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> withNegativeExpiry() {
        return hapiTest(
                cryptoCreate(TOKEN_TREASURY),
                cryptoCreate("autoRenewAccount"),
                atomicBatch(tokenCreate(token)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .supplyKey(GENESIS)
                                .initialSupply(0L)
                                .treasury(TOKEN_TREASURY)
                                .expiry(-1)
                                .hasKnownStatus(INVALID_EXPIRATION_TIME)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> tokenCreateWithAutoRenewAccountAndNoPeriod() {
        return hapiTest(
                cryptoCreate(TOKEN_TREASURY),
                cryptoCreate("autoRenewAccount"),
                atomicBatch(tokenCreate(token)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .supplyKey(GENESIS)
                                .initialSupply(0L)
                                .treasury(TOKEN_TREASURY)
                                .autoRenewAccount("autoRenewAccount")
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR));
    }

    @HapiTest
    final Stream<DynamicTest> tokenCreateWithAutoRenewPeriodAndNoAccount() {
        return hapiTest(
                cryptoCreate(TOKEN_TREASURY),
                atomicBatch(tokenCreate(token)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .supplyKey(GENESIS)
                                .initialSupply(0L)
                                .treasury(TOKEN_TREASURY)
                                .autoRenewPeriod(ONE_MONTH_IN_SECONDS)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR));
    }

    private final long hbarAmount = 1_234L;
    private final long htsAmount = 2_345L;
    private final long numerator = 1;
    private final long denominator = 10;
    private final long minimumToCollect = 5;
    private final long maximumToCollect = 50;
    private final String token = "withCustomSchedules";
    private final String feeDenom = "denom";
    private final String hbarCollector = "hbarFee";
    private final String htsCollector = "denomFee";
    private final String tokenCollector = "fractionalFee";
    private final String customFeesKey = "antique";
}
