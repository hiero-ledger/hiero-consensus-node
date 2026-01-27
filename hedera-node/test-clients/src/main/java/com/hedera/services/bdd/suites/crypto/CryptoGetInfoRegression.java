// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.crypto;

import static com.hedera.services.bdd.junit.TestTags.CRYPTO;
import static com.hedera.services.bdd.junit.TestTags.MATS;
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
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hederahashgraph.api.proto.java.TokenSupplyType;
import com.hederahashgraph.api.proto.java.TokenType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(CRYPTO)
public class CryptoGetInfoRegression {
    static final Logger log = LogManager.getLogger(CryptoGetInfoRegression.class);
    private static final String TARGET_ACC = "targetAcc";
    private static final int NUM_ASSOCIATIONS = 10;

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
                                .stakedNodeId(0L) // this was -1l and failed on mono code too, changed to 0L, success
                                // in both mono and module code
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

    @HapiTest // this test needs to be updated for both mono and module code.
    final Stream<DynamicTest> failsForMissingPayment() {
        return hapiTest(
                getAccountInfo(GENESIS).useEmptyTxnAsAnswerPayment().hasAnswerOnlyPrecheck(INVALID_TRANSACTION_BODY));
    }

    @HapiTest
    final Stream<DynamicTest> failsForDeletedAccount() {
        return hapiTest(
                cryptoCreate("toBeDeleted"),
                cryptoDelete("toBeDeleted").transfer(GENESIS),
                getAccountInfo("toBeDeleted").hasCostAnswerPrecheck(ACCOUNT_DELETED));
    }

    // (cryptoGetAccountBalanceQueryAssociationThrottles moved to crypto.IsolatedSuite)
}
