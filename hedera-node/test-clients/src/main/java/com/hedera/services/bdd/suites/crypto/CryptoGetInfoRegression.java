// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.crypto;

import static com.hedera.services.bdd.junit.ContextRequirement.PROPERTY_OVERRIDES;
import static com.hedera.services.bdd.junit.ContextRequirement.THROTTLE_OVERRIDES;
import static com.hedera.services.bdd.junit.TestTags.CRYPTO;
import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.spec.HapiPropertySource.asAccount;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.accountWith;
import static com.hedera.services.bdd.spec.keys.KeyShape.SIMPLE;
import static com.hedera.services.bdd.spec.keys.KeyShape.listOf;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.*;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.grantTokenKyc;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.*;
import static com.hedera.services.bdd.suites.HapiSuite.CIVILIAN_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.TOKEN_TREASURY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.*;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BUSY;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;
import static java.util.Objects.requireNonNull;
import static org.hiero.base.utility.CommonUtils.unhex;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hederahashgraph.api.proto.java.AccountAmount;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.TokenSupplyType;
import com.hederahashgraph.api.proto.java.TokenType;
import com.hederahashgraph.api.proto.java.TransferList;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;

/**
 * Consolidated test class for crypto get info and query payment tests.
 * Contains tests for account info retrieval and query payment validation.
 */
@Tag(CRYPTO)
public class CryptoGetInfoRegression {
    static final Logger log = LogManager.getLogger(CryptoGetInfoRegression.class);

    // Shared constants
    private static final String TARGET_ACC = "targetAcc";
    private static final int NUM_ASSOCIATIONS = 10;

    @Nested
    @DisplayName("Account info queries")
    class AccountInfoQueries {

        /** For Demo purpose : The limit on each account info and account balance queries is set to 5 */
        @LeakyHapiTest(overrides = {"tokens.maxRelsPerInfoQuery"})
        @Tag(MATS)
        final Stream<DynamicTest> fetchesOnlyALimitedTokenAssociations() {
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
                            .supplyType(TokenSupplyType.FINITE)
                            .tokenType(TokenType.FUNGIBLE_COMMON)
                            .treasury(TOKEN_TREASURY)
                            .maxSupply(500)
                            .kycKey(aKey)
                            .initialSupply(100),
                    tokenCreate(token2)
                            .supplyType(TokenSupplyType.FINITE)
                            .tokenType(TokenType.FUNGIBLE_COMMON)
                            .treasury(TOKEN_TREASURY)
                            .maxSupply(500)
                            .kycKey(aKey)
                            .initialSupply(100),
                    tokenCreate(token3)
                            .supplyType(TokenSupplyType.FINITE)
                            .tokenType(TokenType.FUNGIBLE_COMMON)
                            .treasury(TOKEN_TREASURY)
                            .maxSupply(500)
                            .kycKey(aKey)
                            .initialSupply(100),
                    tokenCreate(token4)
                            .supplyType(TokenSupplyType.FINITE)
                            .tokenType(TokenType.FUNGIBLE_COMMON)
                            .treasury(TOKEN_TREASURY)
                            .maxSupply(500)
                            .kycKey(aKey)
                            .initialSupply(100),
                    tokenCreate(token5)
                            .supplyType(TokenSupplyType.FINITE)
                            .tokenType(TokenType.FUNGIBLE_COMMON)
                            .treasury(TOKEN_TREASURY)
                            .maxSupply(500)
                            .kycKey(aKey)
                            .initialSupply(100),
                    tokenCreate(token6)
                            .supplyType(TokenSupplyType.FINITE)
                            .tokenType(TokenType.FUNGIBLE_COMMON)
                            .treasury(TOKEN_TREASURY)
                            .maxSupply(500)
                            .kycKey(aKey)
                            .initialSupply(100),
                    tokenCreate(token7)
                            .supplyType(TokenSupplyType.FINITE)
                            .tokenType(NON_FUNGIBLE_UNIQUE)
                            .treasury(TOKEN_TREASURY)
                            .maxSupply(10L)
                            .initialSupply(0L)
                            .kycKey(aKey)
                            .supplyKey(aKey),
                    tokenCreate(token8)
                            .supplyType(TokenSupplyType.FINITE)
                            .tokenType(NON_FUNGIBLE_UNIQUE)
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

        @HapiTest
        final Stream<DynamicTest> succeedsNormally() {
            long balance = 1_234_567L;
            KeyShape misc = listOf(SIMPLE, listOf(2));
            final var stakedAccountId = 20;

            return hapiTest(
                    newKeyNamed("misc").shape(misc),
                    cryptoCreate("noStakingTarget").key("misc").balance(balance),
                    cryptoCreate("target").key("misc").balance(balance).stakedNodeId(0L),
                    cryptoCreate("targetWithStakedAccountId")
                            .key("misc")
                            .balance(balance)
                            .stakedAccountId("20"),
                    getAccountInfo("noStakingTarget")
                            .has(accountWith()
                                    .accountId("noStakingTarget")
                                    .stakedNodeId(0L)
                                    .noStakedAccountId()
                                    .key("misc")
                                    .balance(balance))
                            .logged(),
                    getAccountInfo("target")
                            .has(accountWith()
                                    .accountId("target")
                                    .noStakingNodeId()
                                    .key("misc")
                                    .balance(balance))
                            .logged(),
                    getAccountInfo("targetWithStakedAccountId")
                            .has(accountWith()
                                    .accountId("targetWithStakedAccountId")
                                    .stakedAccountId(stakedAccountId)
                                    .key("misc")
                                    .balance(balance))
                            .logged());
        }

        @HapiTest
        final Stream<DynamicTest> failsForMissingAccount() {
            return hapiTest(getAccountInfo("5.5.3").hasCostAnswerPrecheck(INVALID_ACCOUNT_ID));
        }

        @HapiTest
        final Stream<DynamicTest> failsForMalformedPayment() {
            return hapiTest(
                    newKeyNamed("wrong").shape(SIMPLE),
                    getAccountInfo(GENESIS).signedBy("wrong").hasAnswerOnlyPrecheck(INVALID_SIGNATURE));
        }

        @HapiTest
        final Stream<DynamicTest> failsForUnfundablePayment() {
            long everything = 1_234L;
            return hapiTest(
                    cryptoCreate("brokePayer").balance(everything),
                    getAccountInfo(GENESIS)
                            .payingWith("brokePayer")
                            .nodePayment(everything)
                            .hasAnswerOnlyPrecheck(INSUFFICIENT_PAYER_BALANCE));
        }

        @HapiTest
        final Stream<DynamicTest> failsForInsufficientPayment() {
            return hapiTest(
                    cryptoCreate(CIVILIAN_PAYER),
                    getAccountInfo(GENESIS)
                            .payingWith(CIVILIAN_PAYER)
                            .nodePayment(1L)
                            .hasAnswerOnlyPrecheck(INSUFFICIENT_TX_FEE));
        }

        @HapiTest
        final Stream<DynamicTest> failsForMissingPayment() {
            return hapiTest(getAccountInfo(GENESIS)
                    .useEmptyTxnAsAnswerPayment()
                    .hasAnswerOnlyPrecheck(INVALID_TRANSACTION_BODY));
        }

        @HapiTest
        final Stream<DynamicTest> failsForDeletedAccount() {
            return hapiTest(
                    cryptoCreate("toBeDeleted"),
                    cryptoDelete("toBeDeleted").transfer(GENESIS),
                    getAccountInfo("toBeDeleted").hasCostAnswerPrecheck(ACCOUNT_DELETED));
        }

        @LeakyHapiTest(
                requirement = {PROPERTY_OVERRIDES, THROTTLE_OVERRIDES},
                overrides = {"tokens.countingGetBalanceThrottleEnabled"},
                throttles = "testSystemFiles/tiny-get-balance-throttle.json")
        public Stream<DynamicTest> cryptoGetAccountBalanceQueryAssociationThrottles() {
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
    }

    @Nested
    @DisplayName("Query payment validation")
    class QueryPaymentValidation {

        private static final String NODE = "3";

        /**
         * Tests verified:
         * 1. multiple payers pay amount to node as well as one more beneficiary. But node gets less query payment fee
         * 2. TransactionPayer will pay for query payment to node and payer has less balance
         * 3. Transaction payer is not involved in transfers for query payment to node and one or more have less balance
         */
        @HapiTest
        final Stream<DynamicTest> queryPaymentsFailsWithInsufficientFunds() {
            return hapiTest(
                    cryptoCreate("a").balance(500_000_000L),
                    cryptoCreate("b").balance(1_234L),
                    cryptoCreate("c").balance(1_234L),
                    cryptoCreate("d").balance(1_234L),
                    getAccountInfo(GENESIS)
                            .withPayment(cryptoTransfer(innerSpec -> multiAccountPaymentToNode003AndBeneficiary(
                                            innerSpec, "a", "b", "c", 1_000L, 2L))
                                    .payingWith("a"))
                            .setNode(NODE)
                            .hasAnswerOnlyPrecheck(INSUFFICIENT_TX_FEE),
                    getAccountInfo(GENESIS)
                            .withPayment(cryptoTransfer(innerSpec -> multiAccountPaymentToNode003AndBeneficiary(
                                            innerSpec, "d", "b", "c", 5000, 200L))
                                    .payingWith("a"))
                            .setNode(NODE)
                            .hasAnswerOnlyPrecheck(INSUFFICIENT_PAYER_BALANCE),
                    getAccountInfo(GENESIS)
                            .withPayment(cryptoTransfer(innerSpec -> multiAccountPaymentToNode003AndBeneficiary(
                                            innerSpec, "d", GENESIS, "c", 5000, 200L))
                                    .payingWith("a"))
                            .setNode(NODE)
                            .hasAnswerOnlyPrecheck(INSUFFICIENT_PAYER_BALANCE));
        }

        /**
         * Tests verified:
         * 1. multiple payers pay amount to node as well as one more beneficiary. But node gets correct query payment fee
         * 2. TransactionPayer will pay for query payment to node and payer has enough balance
         * 3. Transaction payer is not involved in transfers for query payment to node and all payers have enough balance
         */
        @HapiTest
        @Tag(MATS)
        final Stream<DynamicTest> queryPaymentsMultiBeneficiarySucceeds() {
            return hapiTest(
                    cryptoCreate("a").balance(1_234L),
                    cryptoCreate("b").balance(1_234L),
                    cryptoCreate("c").balance(1_234L),
                    getAccountInfo(GENESIS)
                            .withPayment(cryptoTransfer(innerSpec ->
                                    multiAccountPaymentToNode003AndBeneficiary(innerSpec, "a", "b", "c", 1_000L, 200L)))
                            .setNode(NODE)
                            .hasAnswerOnlyPrecheck(OK),
                    getAccountInfo(GENESIS)
                            .withPayment(cryptoTransfer(innerSpec ->
                                    multiAccountPaymentToNode003AndBeneficiary(innerSpec, "a", "b", "c", 900, 200L)))
                            .setNode(NODE)
                            .payingWith("a")
                            .hasAnswerOnlyPrecheck(OK),
                    getAccountInfo(GENESIS)
                            .withPayment(cryptoTransfer(innerSpec ->
                                    multiAccountPaymentToNode003AndBeneficiary(innerSpec, "a", "b", "c", 1200, 200L)))
                            .setNode(NODE)
                            .payingWith("a")
                            .fee(10L)
                            .hasAnswerOnlyPrecheck(OK));
        }

        // Check if multiple payers or single payer pay amount to node
        @HapiTest
        final Stream<DynamicTest> queryPaymentsSingleBeneficiaryChecked() {
            return hapiTest(
                    cryptoCreate("a").balance(500_000_000L),
                    cryptoCreate("b").balance(1_234L),
                    cryptoCreate("c").balance(1_234L),
                    getAccountInfo(GENESIS).fee(100L).setNode(NODE).hasAnswerOnlyPrecheck(OK),
                    getAccountInfo(GENESIS)
                            .payingWith("a")
                            .nodePayment(Long.MAX_VALUE)
                            .setNode(NODE)
                            .hasAnswerOnlyPrecheck(INSUFFICIENT_PAYER_BALANCE),
                    getAccountInfo(GENESIS)
                            .withPayment(cryptoTransfer(
                                    innerSpec -> multiAccountPaymentToNode003(innerSpec, "a", "b", 1_000L)))
                            .hasAnswerOnlyPrecheck(OK));
        }

        // Check if payment is not done to node
        @HapiTest
        final Stream<DynamicTest> queryPaymentsNotToNodeFails() {
            return hapiTest(
                    cryptoCreate("a").balance(500_000_000L),
                    cryptoCreate("b").balance(1_234L),
                    cryptoCreate("c").balance(1_234L),
                    getAccountInfo(GENESIS)
                            .withPayment(
                                    cryptoTransfer(innerSpec -> invalidPaymentToNode(innerSpec, "a", "b", "c", 1200))
                                            .payingWith("a"))
                            .setNode(NODE)
                            .fee(10L)
                            .hasAnswerOnlyPrecheck(INVALID_RECEIVING_NODE_ACCOUNT));
        }

        private TransferList multiAccountPaymentToNode003(HapiSpec spec, String first, String second, long amount) {
            return TransferList.newBuilder()
                    .addAccountAmounts(adjust(spec.registry().getAccountID(first), -amount / 2))
                    .addAccountAmounts(adjust(spec.registry().getAccountID(second), -amount / 2))
                    .addAccountAmounts(adjust(asAccount(spec, Long.parseLong(NODE)), amount))
                    .build();
        }

        private TransferList invalidPaymentToNode(
                HapiSpec spec, String first, String second, String node, long amount) {
            return TransferList.newBuilder()
                    .addAccountAmounts(adjust(spec.registry().getAccountID(first), -amount / 2))
                    .addAccountAmounts(adjust(spec.registry().getAccountID(second), -amount / 2))
                    .addAccountAmounts(adjust(spec.registry().getAccountID(node), amount))
                    .build();
        }

        private TransferList multiAccountPaymentToNode003AndBeneficiary(
                HapiSpec spec, String first, String second, String beneficiary, long amount, long queryFee) {
            return TransferList.newBuilder()
                    .addAccountAmounts(adjust(spec.registry().getAccountID(first), -amount / 2))
                    .addAccountAmounts(adjust(spec.registry().getAccountID(second), -amount / 2))
                    .addAccountAmounts(adjust(spec.registry().getAccountID(beneficiary), amount - queryFee))
                    .addAccountAmounts(adjust(asAccount(spec, Long.parseLong(NODE)), queryFee))
                    .build();
        }

        private AccountAmount adjust(AccountID id, long amount) {
            return AccountAmount.newBuilder().setAccountID(id).setAmount(amount).build();
        }
    }
}
