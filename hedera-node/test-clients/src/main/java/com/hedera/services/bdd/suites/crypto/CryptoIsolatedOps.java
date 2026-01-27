// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.crypto;

import static com.hedera.services.bdd.junit.ContextRequirement.FEE_SCHEDULE_OVERRIDES;
import static com.hedera.services.bdd.junit.ContextRequirement.PROPERTY_OVERRIDES;
import static com.hedera.services.bdd.junit.ContextRequirement.SYSTEM_ACCOUNT_BALANCES;
import static com.hedera.services.bdd.junit.ContextRequirement.THROTTLE_OVERRIDES;
import static com.hedera.services.bdd.spec.HapiSpec.customizedHapiTest;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.accountWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAliasedAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAliasedAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getReceipt;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenNftInfo;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.isEndOfStakingPeriodRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoApproveAllowance;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDeleteAllowance;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.grantTokenKyc;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromToWithAlias;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertionsHold;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.balanceSnapshot;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.childRecordsCheck;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingAllOf;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingTwo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.reduceFeeFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.THREE_MONTHS_IN_SECONDS;
import static com.hedera.services.bdd.suites.HapiSuite.TOKEN_TREASURY;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.CryptoTransfer;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BUSY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_ACCOUNT_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_MAX_AUTO_ASSOCIATIONS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MAX_ALLOWANCES_EXCEEDED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.PAYER_ACCOUNT_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static java.util.Objects.requireNonNull;
import static org.hiero.base.utility.CommonUtils.unhex;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.spec.SpecOperation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;

/**
 * Helper operations for isolated/leaky CRYPTO tests.
 *
 * <p>These methods are intentionally not JUnit tests; they are invoked by {@link IsolatedSuite}
 * which carries the {@code ISOLATED} tag.
 */
final class CryptoIsolatedOps {
    private CryptoIsolatedOps() {}

    // Copied constants needed by moved methods
    private static final String VALID_ALIAS = "validAlias";
    private static final String CIVILIAN = "somebody";
    private static final String PAYER = "payer";
    private static final String SPONSOR = "autoCreateSponsor";
    private static final long INITIAL_BALANCE = 1000L;
    private static final String TRANSFER_TXN = "transferTxn";
    private static final String AUTO_MEMO = "";
    private static final long EXPECTED_HBAR_TRANSFER_AUTO_CREATION_FEE = 39_376_619L;

    static Stream<DynamicTest> createFailsIfMaxAutoAssocIsNegativeAndUnlimitedFlagDisabled() {
        // moved from CryptoCreateSuite
        return hapiTest(
                overriding("entities.unlimitedAutoAssociationsEnabled", com.hedera.services.bdd.suites.HapiSuite.FALSE_VALUE),
                cryptoCreate(CIVILIAN)
                        .balance(0L)
                        .maxAutomaticTokenAssociations(-1)
                        .hasKnownStatus(INVALID_MAX_AUTO_ASSOCIATIONS),
                cryptoCreate(CIVILIAN)
                        .balance(0L)
                        .maxAutomaticTokenAssociations(-2)
                        .hasPrecheck(INVALID_MAX_AUTO_ASSOCIATIONS),
                cryptoCreate(CIVILIAN)
                        .balance(0L)
                        .maxAutomaticTokenAssociations(-1000)
                        .hasPrecheck(INVALID_MAX_AUTO_ASSOCIATIONS),
                cryptoCreate(CIVILIAN)
                        .balance(0L)
                        .maxAutomaticTokenAssociations(Integer.MIN_VALUE)
                        .hasPrecheck(INVALID_MAX_AUTO_ASSOCIATIONS));
    }

    static Stream<DynamicTest> autoAccountCreationsUnlimitedAssociationsDisabled() {
        // moved from AutoAccountCreationUnlimitedAssociationsSuite
        final var creationTime = new AtomicLong();
        final long transferFee = 188608L;
        return customizedHapiTest(
                Map.of("memo.useSpecName", "false"),
                overriding("entities.unlimitedAutoAssociationsEnabled", AutoAccountCreationUnlimitedAssociationsSuite.FALSE),
                newKeyNamed(VALID_ALIAS),
                cryptoCreate(CIVILIAN).balance(10 * ONE_HBAR),
                cryptoCreate(PAYER).balance(10 * ONE_HBAR),
                cryptoCreate(SPONSOR).balance(INITIAL_BALANCE * ONE_HBAR),
                cryptoTransfer(
                                tinyBarsFromToWithAlias(SPONSOR, VALID_ALIAS, ONE_HUNDRED_HBARS),
                                tinyBarsFromToWithAlias(CIVILIAN, VALID_ALIAS, ONE_HBAR))
                        .via(TRANSFER_TXN)
                        .payingWith(PAYER),
                getReceipt(TRANSFER_TXN).andAnyChildReceipts().hasChildAutoAccountCreations(1),
                com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord(TRANSFER_TXN).andAllChildRecords().logged(),
                getAccountInfo(SPONSOR).has(accountWith()
                        .balance((INITIAL_BALANCE * ONE_HBAR) - ONE_HUNDRED_HBARS)
                        .noAlias()),
                childRecordsCheck(
                        TRANSFER_TXN,
                        SUCCESS,
                        recordWith().status(SUCCESS).fee(EXPECTED_HBAR_TRANSFER_AUTO_CREATION_FEE)),
                assertionsHold((spec, opLog) -> {
                    final var lookup = com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord(TRANSFER_TXN)
                            .andAllChildRecords()
                            .hasNonStakingChildRecordCount(1)
                            .hasNoAliasInChildRecord(0)
                            .logged();
                    allRunFor(spec, lookup);
                    final var sponsor = spec.registry().getAccountID(SPONSOR);
                    final var payer = spec.registry().getAccountID(PAYER);
                    final var parent = lookup.getResponseRecord();
                    var child = lookup.getChildRecord(0);
                    if (isEndOfStakingPeriodRecord(child)) {
                        child = lookup.getChildRecord(1);
                    }
                    AutoAccountCreationSuite.assertAliasBalanceAndFeeInChildRecord(
                            parent, child, sponsor, payer, ONE_HUNDRED_HBARS + ONE_HBAR, transferFee, 0);
                    creationTime.set(child.getConsensusTimestamp().getSeconds());
                }),
                sourcing(() -> getAliasedAccountInfo(VALID_ALIAS)
                        .has(accountWith()
                                .key(VALID_ALIAS)
                                .expectedBalanceWithChargedUsd(ONE_HUNDRED_HBARS + ONE_HBAR, 0, 0)
                                .alias(VALID_ALIAS)
                                .autoRenew(THREE_MONTHS_IN_SECONDS)
                                .receiverSigReq(false)
                                .expiry(creationTime.get() + THREE_MONTHS_IN_SECONDS, 0)
                                .memo(AUTO_MEMO)
                                .maxAutoAssociations(0))
                        .logged()));
    }

    static Stream<DynamicTest> deletedAccountCannotBePayer() {
        // moved from CryptoDeleteSuite
        final var submittingNodeAccount = "3";
        final var beneficiaryAccount = "beneficiaryAccountForDeletedAccount";
        final var submittingNodePreTransfer = "submittingNodePreTransfer";
        final var submittingNodeAfterBalanceLoad = "submittingNodeAfterBalanceLoad";
        return hapiTest(
                cryptoCreate("toBeDeleted"),
                cryptoCreate(beneficiaryAccount).balance(0L),
                balanceSnapshot(submittingNodePreTransfer, submittingNodeAccount),
                cryptoTransfer(tinyBarsFromTo(GENESIS, submittingNodeAccount, 1000000000)),
                balanceSnapshot(submittingNodeAfterBalanceLoad, submittingNodeAccount),
                cryptoDelete("toBeDeleted").transfer(beneficiaryAccount).deferStatusResolution(),
                cryptoTransfer(tinyBarsFromTo(beneficiaryAccount, GENESIS, 1))
                        .payingWith("toBeDeleted")
                        .hasKnownStatus(PAYER_ACCOUNT_DELETED),
                getAccountBalance(submittingNodeAccount)
                        .hasTinyBars(
                                com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.approxChangeFromSnapshot(
                                        submittingNodeAfterBalanceLoad, -30000, 15000))
                        .logged());
    }

    static Stream<DynamicTest> reduceTransferFee() {
        // moved from MiscCryptoSuite
        final long REDUCED_NODE_FEE = 2L;
        final long REDUCED_NETWORK_FEE = 3L;
        final long REDUCED_SERVICE_FEE = 3L;
        final long REDUCED_TOTAL_FEE = REDUCED_NODE_FEE + REDUCED_NETWORK_FEE + REDUCED_SERVICE_FEE;
        return hapiTest(
                cryptoCreate("sender").balance(ONE_HUNDRED_HBARS),
                cryptoCreate("receiver").balance(0L),
                cryptoTransfer(tinyBarsFromTo("sender", "receiver", ONE_HBAR))
                        .payingWith("sender")
                        .fee(REDUCED_TOTAL_FEE)
                        .hasPrecheck(INSUFFICIENT_TX_FEE),
                reduceFeeFor(CryptoTransfer, REDUCED_NODE_FEE, REDUCED_NETWORK_FEE, REDUCED_SERVICE_FEE),
                cryptoTransfer(tinyBarsFromTo("sender", "receiver", ONE_HBAR))
                        .payingWith("sender")
                        .fee(ONE_HBAR),
                getAccountBalance("sender").hasTinyBars(ONE_HUNDRED_HBARS - ONE_HBAR - REDUCED_TOTAL_FEE));
    }

    static Stream<DynamicTest> exceedsTransactionLimit() {
        // moved from CryptoDeleteAllowanceSuite
        final String owner = "owner";
        final String spender = "spender";
        final String token = "token";
        final String nft = "nft";
        return hapiTest(
                overriding("hedera.allowances.maxTransactionLimit", "4"),
                newKeyNamed("supplyKey"),
                cryptoCreate(owner).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                cryptoCreate(spender).balance(ONE_HUNDRED_HBARS),
                cryptoCreate("spender1").balance(ONE_HUNDRED_HBARS),
                cryptoCreate("spender2").balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY).balance(100 * ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                tokenCreate(token)
                        .tokenType(com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON)
                        .supplyType(com.hederahashgraph.api.proto.java.TokenSupplyType.FINITE)
                        .supplyKey("supplyKey")
                        .maxSupply(1000L)
                        .initialSupply(10L)
                        .treasury(TOKEN_TREASURY),
                tokenCreate(nft)
                        .maxSupply(10L)
                        .initialSupply(0)
                        .supplyType(com.hederahashgraph.api.proto.java.TokenSupplyType.FINITE)
                        .tokenType(com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE)
                        .supplyKey("supplyKey")
                        .treasury(TOKEN_TREASURY),
                tokenAssociate(owner, token),
                tokenAssociate(owner, nft),
                mintToken(nft, List.of(ByteString.copyFromUtf8("a"), ByteString.copyFromUtf8("b"), ByteString.copyFromUtf8("c")))
                        .via("nftTokenMint"),
                mintToken(token, 500L).via("tokenMint"),
                cryptoTransfer(movingUnique(nft, 1L, 2L, 3L).between(TOKEN_TREASURY, owner)),
                cryptoApproveAllowance()
                        .payingWith(owner)
                        .addNftAllowance(owner, nft, spender, false, List.of(1L, 2L)),
                cryptoDeleteAllowance()
                        .payingWith(owner)
                        .addNftDeleteAllowance(owner, nft, List.of(1L, 2L, 3L, 3L, 3L))
                        .hasPrecheck(MAX_ALLOWANCES_EXCEEDED),
                cryptoDeleteAllowance()
                        .payingWith(owner)
                        .addNftDeleteAllowance(owner, nft, List.of(1L, 1L, 1L, 1L, 1L))
                        .hasPrecheck(MAX_ALLOWANCES_EXCEEDED),
                cryptoDeleteAllowance()
                        .payingWith(owner)
                        .addNftDeleteAllowance(owner, nft, List.of(1L))
                        .addNftDeleteAllowance(owner, nft, List.of(2L))
                        .addNftDeleteAllowance(owner, nft, List.of(3L))
                        .addNftDeleteAllowance(owner, nft, List.of(1L))
                        .addNftDeleteAllowance(owner, nft, List.of(1L))
                        .hasPrecheck(MAX_ALLOWANCES_EXCEEDED));
    }

    // Copied from CryptoGetInfoRegression
    private static final String TARGET_ACC = "targetAcc";
    private static final int NUM_ASSOCIATIONS = 10;

    static Stream<DynamicTest> fetchesOnlyALimitedTokenAssociations() {
        final var account = "test";
        final var aKey = "tokenKey";
        final var token1 = "token1";
        final var token2 = "token2";
        final var token3 = "token3";
        final var token4 = "token4";
        final var token5 = "token5";
        final var token6 = "token6";
        final var token7 = "token7";
        final var token8 = "token8";
        return hapiTest(
                overriding("tokens.maxRelsPerInfoQuery", "" + 1),
                newKeyNamed(aKey),
                cryptoCreate(account).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY).balance(ONE_HUNDRED_HBARS),
                tokenCreate(token1)
                        .supplyType(com.hederahashgraph.api.proto.java.TokenSupplyType.FINITE)
                        .tokenType(com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON)
                        .treasury(TOKEN_TREASURY)
                        .maxSupply(500)
                        .kycKey(aKey)
                        .initialSupply(100),
                tokenCreate(token2)
                        .supplyType(com.hederahashgraph.api.proto.java.TokenSupplyType.FINITE)
                        .tokenType(com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON)
                        .treasury(TOKEN_TREASURY)
                        .maxSupply(500)
                        .kycKey(aKey)
                        .initialSupply(100),
                tokenCreate(token3)
                        .supplyType(com.hederahashgraph.api.proto.java.TokenSupplyType.FINITE)
                        .tokenType(com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON)
                        .treasury(TOKEN_TREASURY)
                        .maxSupply(500)
                        .kycKey(aKey)
                        .initialSupply(100),
                tokenCreate(token4)
                        .supplyType(com.hederahashgraph.api.proto.java.TokenSupplyType.FINITE)
                        .tokenType(com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON)
                        .treasury(TOKEN_TREASURY)
                        .maxSupply(500)
                        .kycKey(aKey)
                        .initialSupply(100),
                tokenCreate(token5)
                        .supplyType(com.hederahashgraph.api.proto.java.TokenSupplyType.FINITE)
                        .tokenType(com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON)
                        .treasury(TOKEN_TREASURY)
                        .maxSupply(500)
                        .kycKey(aKey)
                        .initialSupply(100),
                tokenCreate(token6)
                        .supplyType(com.hederahashgraph.api.proto.java.TokenSupplyType.FINITE)
                        .tokenType(com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON)
                        .treasury(TOKEN_TREASURY)
                        .maxSupply(500)
                        .kycKey(aKey)
                        .initialSupply(100),
                tokenCreate(token7)
                        .supplyType(com.hederahashgraph.api.proto.java.TokenSupplyType.FINITE)
                        .tokenType(com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE)
                        .treasury(TOKEN_TREASURY)
                        .maxSupply(10L)
                        .initialSupply(0L)
                        .kycKey(aKey)
                        .supplyKey(aKey),
                tokenCreate(token8)
                        .supplyType(com.hederahashgraph.api.proto.java.TokenSupplyType.FINITE)
                        .tokenType(com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE)
                        .treasury(TOKEN_TREASURY)
                        .maxSupply(10L)
                        .initialSupply(0L)
                        .kycKey(aKey)
                        .supplyKey(aKey),
                mintToken(token7, List.of(ByteString.copyFromUtf8("a"), ByteString.copyFromUtf8("b"))),
                mintToken(token8, List.of(ByteString.copyFromUtf8("a"))),
                tokenAssociate(account, token1, token2, token3, token4, token5, token6, token7, token8),
                grantTokenKyc(token1, account),
                grantTokenKyc(token2, account),
                grantTokenKyc(token3, account),
                grantTokenKyc(token4, account),
                grantTokenKyc(token5, account),
                grantTokenKyc(token6, account),
                grantTokenKyc(token7, account),
                grantTokenKyc(token8, account),
                cryptoTransfer(
                        moving(10L, token1).between(TOKEN_TREASURY, account),
                        moving(20L, token2).between(TOKEN_TREASURY, account),
                        moving(30L, token3).between(TOKEN_TREASURY, account)),
                cryptoTransfer(
                        moving(40L, token4).between(TOKEN_TREASURY, account),
                        moving(50L, token5).between(TOKEN_TREASURY, account),
                        moving(60L, token6).between(TOKEN_TREASURY, account)),
                cryptoTransfer(
                        movingUnique(token7, 1, 2).between(TOKEN_TREASURY, account),
                        movingUnique(token8, 1).between(TOKEN_TREASURY, account)),
                overriding("tokens.maxRelsPerInfoQuery", "3"),
                getAccountInfo(account).hasTokenRelationShipCount(3));
    }

    static Stream<DynamicTest> cryptoGetAccountBalanceQueryAssociationThrottles() {
        final var evmHexRef = new AtomicReference<>("");
        final List<String> tokenNames = new ArrayList<>();
        for (int i = 0; i < NUM_ASSOCIATIONS; i++) {
            tokenNames.add("t" + i);
        }
        final var ops = new ArrayList<SpecOperation>();
        ops.add(overridingAllOf(Map.of("tokens.countingGetBalanceThrottleEnabled", "true")));
        ops.add(cryptoCreate(TARGET_ACC).withMatchingEvmAddress());
        tokenNames.forEach(t -> {
            ops.add(tokenCreate(t));
            ops.add(tokenAssociate(TARGET_ACC, t));
        });
        ops.add(getAccountInfo(TARGET_ACC).exposingContractAccountIdTo(evmHexRef::set));
        ops.add(getAccountBalance(TARGET_ACC).hasAnswerOnlyPrecheck(BUSY));
        ops.add(sourcing(() -> getAliasedAccountBalance(ByteString.copyFrom(requireNonNull(unhex(evmHexRef.get()))))
                .hasAnswerOnlyPrecheck(BUSY)));

        return hapiTest(ops.toArray(new SpecOperation[0]));
    }

    static Stream<DynamicTest> usdFeeAsExpectedCryptoUpdate() {
        // moved from CryptoUpdateSuite
        double baseFee = 0.000214;
        double baseFeeWithExpiry = 0.00022;

        final var baseTxn = "baseTxn";
        final var plusOneTxn = "plusOneTxn";
        final var plusTenTxn = "plusTenTxn";
        final var plusFiveKTxn = "plusFiveKTxn";
        final var plusFiveKAndOneTxn = "plusFiveKAndOneTxn";
        final var invalidNegativeTxn = "invalidNegativeTxn";
        final var validNegativeTxn = "validNegativeTxn";
        final var allowedPercentDiff = 1.5;

        AtomicLong expiration = new AtomicLong();
        return hapiTest(
                overridingTwo("ledger.maxAutoAssociations", "5000", "entities.maxLifetime", "3153600000"),
                newKeyNamed("key").shape(com.hedera.services.bdd.spec.keys.KeyShape.SIMPLE),
                cryptoCreate("payer").key("key").balance(1_000 * ONE_HBAR),
                cryptoCreate("canonicalAccount")
                        .key("key")
                        .balance(100 * ONE_HBAR)
                        .autoRenewSecs(THREE_MONTHS_IN_SECONDS)
                        .blankMemo()
                        .payingWith("payer"),
                cryptoCreate("autoAssocTarget")
                        .key("key")
                        .balance(100 * ONE_HBAR)
                        .autoRenewSecs(THREE_MONTHS_IN_SECONDS)
                        .blankMemo()
                        .payingWith("payer"),
                getAccountInfo("canonicalAccount").exposingExpiry(expiration::set),
                sourcing(() -> cryptoUpdate("canonicalAccount")
                        .payingWith("canonicalAccount")
                        .expiring(expiration.get() + THREE_MONTHS_IN_SECONDS)
                        .blankMemo()
                        .via(baseTxn)),
                getAccountInfo("canonicalAccount").hasMaxAutomaticAssociations(0).logged(),
                cryptoUpdate("autoAssocTarget")
                        .payingWith("autoAssocTarget")
                        .blankMemo()
                        .maxAutomaticAssociations(1)
                        .via(plusOneTxn),
                getAccountInfo("autoAssocTarget").hasMaxAutomaticAssociations(1).logged(),
                cryptoUpdate("autoAssocTarget")
                        .payingWith("autoAssocTarget")
                        .blankMemo()
                        .maxAutomaticAssociations(11)
                        .via(plusTenTxn),
                getAccountInfo("autoAssocTarget").hasMaxAutomaticAssociations(11).logged(),
                cryptoUpdate("autoAssocTarget")
                        .payingWith("autoAssocTarget")
                        .blankMemo()
                        .maxAutomaticAssociations(5000)
                        .via(plusFiveKTxn),
                getAccountInfo("autoAssocTarget").hasMaxAutomaticAssociations(5000).logged(),
                cryptoUpdate("autoAssocTarget")
                        .payingWith("autoAssocTarget")
                        .blankMemo()
                        .maxAutomaticAssociations(-1000)
                        .via(invalidNegativeTxn)
                        .hasKnownStatus(INVALID_MAX_AUTO_ASSOCIATIONS),
                cryptoUpdate("autoAssocTarget")
                        .payingWith("autoAssocTarget")
                        .blankMemo()
                        .maxAutomaticAssociations(5001)
                        .via(plusFiveKAndOneTxn)
                        .hasKnownStatus(com.hederahashgraph.api.proto.java.ResponseCodeEnum
                                .REQUESTED_NUM_AUTOMATIC_ASSOCIATIONS_EXCEEDS_ASSOCIATION_LIMIT),
                cryptoUpdate("autoAssocTarget")
                        .payingWith("autoAssocTarget")
                        .blankMemo()
                        .maxAutomaticAssociations(-1)
                        .via(validNegativeTxn),
                getAccountInfo("autoAssocTarget").hasMaxAutomaticAssociations(-1).logged(),
                com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsd(baseTxn, baseFeeWithExpiry, allowedPercentDiff),
                com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsd(plusOneTxn, baseFee, allowedPercentDiff),
                com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsd(plusTenTxn, baseFee, allowedPercentDiff),
                com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsd(plusFiveKTxn, baseFee, allowedPercentDiff),
                com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsd(validNegativeTxn, baseFee, allowedPercentDiff));
    }
}

