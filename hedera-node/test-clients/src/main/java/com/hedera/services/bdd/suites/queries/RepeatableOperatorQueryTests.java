// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.queries;

import static com.hedera.services.bdd.junit.RepeatableReason.NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW;
import static com.hedera.services.bdd.spec.HapiSpec.customizedHapiTest;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.changeFromSnapshot;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.lessThan;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileContents;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getScheduleInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTopicInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.EmbeddedVerbs.handleAnyRepeatableQueryPayment;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.balanceSnapshot;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

import com.hedera.services.bdd.junit.RepeatableHapiTest;
import com.hederahashgraph.api.proto.java.TokenSupplyType;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;

public class RepeatableOperatorQueryTests extends NodeOperatorQueriesBase {
    @RepeatableHapiTest(NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW)
    final Stream<DynamicTest> nodeOperatorQueryVerifyPayerBalanceForAccountBalance() {
        return hapiTest(
                cryptoCreate(NODE_OPERATOR).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                // perform getAccountBalance() query, pay for the query with payer account
                getAccountBalance(NODE_OPERATOR).payingWith(PAYER),
                handleAnyRepeatableQueryPayment(),
                // assert payer is charged
                getAccountBalance(PAYER).hasTinyBars(ONE_HUNDRED_HBARS),
                // perform free query to local port with asNodeOperator() method
                getAccountBalance(NODE_OPERATOR).payingWith(PAYER).asNodeOperator(),
                handleAnyRepeatableQueryPayment(),
                // assert payer is not charged as the query is performed as node operator
                getAccountBalance(PAYER).hasTinyBars(ONE_HUNDRED_HBARS));
    }

    @RepeatableHapiTest(NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW)
    final Stream<DynamicTest> nodeOperatorQueryVerifyPayerBalanceForAccountInfo() {
        return customizedHapiTest(
                Map.of("memo.useSpecName", "false"),
                flattened(
                        nodeOperatorAccount(),
                        payerAccount(),
                        balanceSnapshot("payerInitialBalance", PAYER),
                        // perform getAccountInfo() query, pay for the query with payer account
                        // the grpc client performs the query to different ports
                        getAccountInfo(NODE_OPERATOR).payingWith(PAYER),
                        handleAnyRepeatableQueryPayment(),
                        // assert payer is charged
                        getAccountBalance(PAYER).hasTinyBars(changeFromSnapshot("payerInitialBalance", -QUERY_COST)),
                        // perform free query to local port with asNodeOperator() method
                        getAccountInfo(NODE_OPERATOR).payingWith(PAYER).asNodeOperator(),
                        handleAnyRepeatableQueryPayment(),
                        // assert payer is not charged as the query is performed as node operator
                        getAccountBalance(PAYER).hasTinyBars(changeFromSnapshot("payerInitialBalance", -QUERY_COST))));
    }

    @RepeatableHapiTest(NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW)
    final Stream<DynamicTest> nodeOperatorTokenInfoQueryNotCharged() {
        return hapiTest(flattened(
                nodeOperatorAccount(),
                tokenCreate(FUNGIBLE_QUERY_TOKEN),
                getTokenInfo(FUNGIBLE_QUERY_TOKEN).payingWith(NODE_OPERATOR).asNodeOperator(),
                handleAnyRepeatableQueryPayment(),
                getAccountBalance(NODE_OPERATOR).hasTinyBars(ONE_HUNDRED_HBARS)));
    }

    @RepeatableHapiTest(NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW)
    final Stream<DynamicTest> nodeOperatorTokenInfoQueryCharged() {
        return hapiTest(flattened(
                nodeOperatorAccount(),
                tokenCreate(ANOTHER_FUNGIBLE_QUERY_TOKEN),
                getTokenInfo(ANOTHER_FUNGIBLE_QUERY_TOKEN).payingWith(NODE_OPERATOR),
                handleAnyRepeatableQueryPayment(),
                getAccountBalance(NODE_OPERATOR).hasTinyBars(lessThan(ONE_HUNDRED_HBARS))));
    }

    @RepeatableHapiTest(NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW)
    final Stream<DynamicTest> nodeOperatorTokenInfoQuerySignature() {
        return hapiTest(flattened(
                nodeOperatorAccount(),
                tokenCreate(ANOTHER_FUNGIBLE_QUERY_TOKEN),
                getTokenInfo(ANOTHER_FUNGIBLE_QUERY_TOKEN)
                        .payingWith(NODE_OPERATOR)
                        .asNodeOperator(),
                handleAnyRepeatableQueryPayment(),
                getAccountBalance(NODE_OPERATOR).hasTinyBars(ONE_HUNDRED_HBARS)));
    }

    @RepeatableHapiTest(NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW)
    final Stream<DynamicTest> nodeOperatorTokenInfoQueryNftNotCharged() {
        return hapiTest(flattened(
                nodeOperatorAccount(),
                newKeyNamed(SUPPLY_KEY),
                newKeyNamed(WIPE_KEY),
                cryptoCreate(TREASURY),
                tokenCreate(NON_FUNGIBLE_TOKEN)
                        .supplyType(TokenSupplyType.FINITE)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .treasury(TREASURY)
                        .maxSupply(12L)
                        .wipeKey(WIPE_KEY)
                        .supplyKey(SUPPLY_KEY)
                        .initialSupply(0L),
                getTokenInfo(NON_FUNGIBLE_TOKEN).payingWith(NODE_OPERATOR).asNodeOperator(),
                handleAnyRepeatableQueryPayment(),
                getAccountBalance(NODE_OPERATOR).hasTinyBars(ONE_HUNDRED_HBARS)));
    }

    @RepeatableHapiTest(NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW)
    final Stream<DynamicTest> nodeOperatorTokenInfoQueryNftCharged() {
        return hapiTest(flattened(
                createAllAccountsAndTokens(),
                newKeyNamed(SUPPLY_KEY),
                newKeyNamed(WIPE_KEY),
                cryptoCreate(TREASURY),
                flattened(
                        nodeOperatorAccount(),
                        tokenCreate(NON_FUNGIBLE_TOKEN)
                                .supplyType(TokenSupplyType.FINITE)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .treasury(TREASURY)
                                .maxSupply(12L)
                                .wipeKey(WIPE_KEY)
                                .supplyKey(SUPPLY_KEY)
                                .initialSupply(0L),
                        getTokenInfo(FUNGIBLE_QUERY_TOKEN).payingWith(NODE_OPERATOR),
                        handleAnyRepeatableQueryPayment(),
                        getAccountBalance(NODE_OPERATOR).hasTinyBars(lessThan(ONE_HUNDRED_HBARS)))));
    }

    @RepeatableHapiTest(NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW)
    final Stream<DynamicTest> nodeOperatorTokenInfoQueryNftSignature() {
        return hapiTest(flattened(
                createAllAccountsAndTokens(),
                newKeyNamed(SUPPLY_KEY),
                newKeyNamed(WIPE_KEY),
                cryptoCreate(TREASURY),
                flattened(
                        nodeOperatorAccount(),
                        tokenCreate(NON_FUNGIBLE_TOKEN)
                                .supplyType(TokenSupplyType.FINITE)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .treasury(TREASURY)
                                .maxSupply(12L)
                                .wipeKey(WIPE_KEY)
                                .supplyKey(SUPPLY_KEY)
                                .initialSupply(0L),
                        getTokenInfo(FUNGIBLE_QUERY_TOKEN)
                                .payingWith(NODE_OPERATOR)
                                .signedBy(DEFAULT_PAYER)
                                .asNodeOperator(),
                        handleAnyRepeatableQueryPayment(),
                        getAccountBalance(NODE_OPERATOR).hasTinyBars(ONE_HUNDRED_HBARS))));
    }

    @RepeatableHapiTest(NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW)
    final Stream<DynamicTest> nodeOperatorTopicInfoQueryNotCharged() {
        return hapiTest(flattened(
                nodeOperatorAccount(),
                createTopic(TOPIC),
                getTopicInfo(TOPIC).payingWith(NODE_OPERATOR).asNodeOperator(),
                handleAnyRepeatableQueryPayment(),
                getAccountBalance(NODE_OPERATOR).hasTinyBars(ONE_HUNDRED_HBARS)));
    }

    @RepeatableHapiTest(NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW)
    final Stream<DynamicTest> nodeOperatorTopicInfoQueryCharged() {
        return hapiTest(flattened(
                nodeOperatorAccount(),
                createTopic(TOPIC),
                getTopicInfo(TOPIC).payingWith(NODE_OPERATOR),
                handleAnyRepeatableQueryPayment(),
                getAccountBalance(NODE_OPERATOR).hasTinyBars(lessThan(ONE_HUNDRED_HBARS))));
    }

    @RepeatableHapiTest(NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW)
    @DisplayName("Only node operators aren't charged for file contents queries")
    final Stream<DynamicTest> fileGetContentsQueryNodeOperatorNotCharged() {
        final var filename = "anyFile.txt";
        return hapiTest(flattened(
                nodeOperatorAccount(),
                payerAccount(),
                fileCreate(filename).contents("anyContent"),
                getFileContents(filename).payingWith(NODE_OPERATOR).asNodeOperator(),
                getFileContents(filename).payingWith(PAYER),
                handleAnyRepeatableQueryPayment(),
                getAccountBalance(NODE_OPERATOR).hasTinyBars(ONE_HUNDRED_HBARS),
                getAccountBalance(PAYER).hasTinyBars(lessThan(ONE_HUNDRED_HBARS))));
    }

    @RepeatableHapiTest(NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW)
    @DisplayName("Only node operators aren't charged for file info queries")
    final Stream<DynamicTest> getFileInfoQueryNodeOperatorNotCharged() {
        final var filename = "anyFile.txt";
        return hapiTest(flattened(
                nodeOperatorAccount(),
                payerAccount(),
                fileCreate(filename).contents("anyContentAgain").payingWith(PAYER),
                // Both the node operator and payer submit queries
                getFileInfo(filename).payingWith(NODE_OPERATOR).asNodeOperator(),
                getFileInfo(filename).payingWith(PAYER),
                handleAnyRepeatableQueryPayment(),
                // The node operator wasn't charged
                getAccountBalance(NODE_OPERATOR).hasTinyBars(ONE_HUNDRED_HBARS),
                // But the payer was charged
                getAccountBalance(PAYER).hasTinyBars(lessThan(ONE_HUNDRED_HBARS))));
    }

    @RepeatableHapiTest(NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW)
    @DisplayName("Only node operators aren't charged for contract info queries")
    final Stream<DynamicTest> getSmartContractQueryNodeOperatorNotCharged() {
        final var contract = "PretendPair"; // any contract, nothing special about this one
        return hapiTest(flattened(
                nodeOperatorAccount(),
                payerAccount(),
                uploadInitCode(contract),
                contractCreate(contract),
                // Both the node operator and payer submit queries
                getContractInfo(contract).payingWith(NODE_OPERATOR).asNodeOperator(),
                getContractInfo(contract).payingWith(PAYER),
                handleAnyRepeatableQueryPayment(),
                // The node operator wasn't charged
                getAccountBalance(NODE_OPERATOR).hasTinyBars(ONE_HUNDRED_HBARS),
                // But the payer was charged
                getAccountBalance(PAYER).hasTinyBars(lessThan(ONE_HUNDRED_HBARS))));
    }

    @RepeatableHapiTest(NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW)
    @DisplayName("Only node operators aren't charged for schedule info queries")
    final Stream<DynamicTest> getScheduleInfoQueryNodeOperatorNotCharged() {
        final var txnToSchedule =
                cryptoTransfer(tinyBarsFromTo(PAYER, DEFAULT_PAYER, 1)); // any txn, nothing special here
        final var schedule = "anySchedule";
        return hapiTest(flattened(
                nodeOperatorAccount(),
                payerAccount(),
                scheduleCreate(schedule, txnToSchedule),
                // Both the node operator and payer submit queries
                getScheduleInfo(schedule).payingWith(NODE_OPERATOR).asNodeOperator(),
                getScheduleInfo(schedule).payingWith(PAYER),
                handleAnyRepeatableQueryPayment(),
                // The node operator wasn't charged
                getAccountBalance(NODE_OPERATOR).hasTinyBars(ONE_HUNDRED_HBARS),
                // But the payer was charged
                getAccountBalance(PAYER).hasTinyBars(lessThan(ONE_HUNDRED_HBARS))));
    }
}
