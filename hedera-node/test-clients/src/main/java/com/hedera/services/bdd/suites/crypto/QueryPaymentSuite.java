// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.crypto;

import static com.hedera.services.bdd.junit.TestTags.CRYPTO;
import static com.hedera.services.bdd.spec.HapiPropertySource.asAccount;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_RECEIVING_NODE_ACCOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hederahashgraph.api.proto.java.AccountAmount;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.TransferList;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(CRYPTO)
public class QueryPaymentSuite {

    private static final String NODE = "3";

    /*
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

    /*
     * Tests verified
     * 1. multiple payers pay amount to node as well as one more beneficiary. But node gets correct query payment fee
     * 2. TransactionPayer will pay for query payment to node and payer has enough balance
     * 3. Transaction payer is not involved in transfers for query payment to node and all payers have enough balance
     */
    @HapiTest
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
                        .withPayment(
                                cryptoTransfer(innerSpec -> multiAccountPaymentToNode003(innerSpec, "a", "b", 1_000L)))
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
                        .withPayment(cryptoTransfer(innerSpec -> invalidPaymentToNode(innerSpec, "a", "b", "c", 1200))
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

    private TransferList invalidPaymentToNode(HapiSpec spec, String first, String second, String node, long amount) {
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

    public AccountAmount adjust(AccountID id, long amount) {
        return AccountAmount.newBuilder().setAccountID(id).setAmount(amount).build();
    }
}
