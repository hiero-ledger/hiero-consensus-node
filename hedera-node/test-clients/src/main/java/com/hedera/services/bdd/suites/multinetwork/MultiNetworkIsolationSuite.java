// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.multinetwork;

import static com.hedera.services.bdd.spec.HapiPropertySource.asAccountString;
import static com.hedera.services.bdd.spec.HapiSpec.multiNetworkHapiTest;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;

import com.hedera.services.bdd.junit.MultiNetworkHapiTest;
import com.hedera.services.bdd.junit.hedera.subprocess.SubProcessNetwork;
import com.hedera.services.bdd.spec.queries.QueryVerbs;
import com.hedera.services.bdd.spec.transactions.TxnVerbs;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;

/**
 * Test suite for multi-network isolation testing.
 * <p>
 * Verifies that multiple Hedera networks running in parallel remain isolated,
 * ensuring that operations on one network do not affect the state or behavior
 * of other networks. Includes tests for account creation, balance queries,
 * and independent ledger counters across distinct networks.
 */
public class MultiNetworkIsolationSuite {

    @MultiNetworkHapiTest(
            networks = {
                @MultiNetworkHapiTest.Network(name = "NET_A", size = 3),
                @MultiNetworkHapiTest.Network(name = "NET_B", size = 2),
                @MultiNetworkHapiTest.Network(name = "NET_C", size = 1)
            })
    @DisplayName("Three network isolation")
    Stream<DynamicTest> threeNetworkIsolation(
            final SubProcessNetwork netA, final SubProcessNetwork netB, final SubProcessNetwork netC) {
        final var acctA = new AtomicReference<String>();
        final var acctB = new AtomicReference<String>();
        final var acctC = new AtomicReference<String>();

        final var builder = multiNetworkHapiTest(netA, netB, netC)
                // Create on A, unseen on B/C
                .onNetwork(
                        "NET_A",
                        TxnVerbs.cryptoCreate("acctA")
                                .balance(ONE_HBAR)
                                .exposingCreatedIdTo(id -> acctA.set(asAccountString(id))),
                        QueryVerbs.getAccountBalance(acctA::get).hasTinyBars(ONE_HBAR))
                .onNetwork(
                        "NET_B",
                        QueryVerbs.getAccountBalance(acctA::get)
                                .hasAnswerOnlyPrecheck(ResponseCodeEnum.INVALID_ACCOUNT_ID))
                .onNetwork(
                        "NET_C",
                        QueryVerbs.getAccountBalance(acctA::get)
                                .hasAnswerOnlyPrecheck(ResponseCodeEnum.INVALID_ACCOUNT_ID))
                // Create on B, unseen on A/C
                .onNetwork(
                        "NET_B",
                        TxnVerbs.cryptoCreate("acctB")
                                .balance(ONE_HBAR)
                                .exposingCreatedIdTo(id -> acctB.set(asAccountString(id))),
                        QueryVerbs.getAccountBalance(acctB::get).hasTinyBars(ONE_HBAR))
                .onNetwork(
                        "NET_A",
                        QueryVerbs.getAccountBalance(acctB::get)
                                .hasAnswerOnlyPrecheck(ResponseCodeEnum.INVALID_ACCOUNT_ID))
                .onNetwork(
                        "NET_C",
                        QueryVerbs.getAccountBalance(acctB::get)
                                .hasAnswerOnlyPrecheck(ResponseCodeEnum.INVALID_ACCOUNT_ID))
                // Create on C, unseen on A/B
                .onNetwork(
                        "NET_C",
                        TxnVerbs.cryptoCreate("acctC")
                                .balance(ONE_HBAR)
                                .exposingCreatedIdTo(id -> acctC.set(asAccountString(id))),
                        QueryVerbs.getAccountBalance(acctC::get).hasTinyBars(ONE_HBAR))
                .onNetwork(
                        "NET_A",
                        QueryVerbs.getAccountBalance(acctC::get)
                                .hasAnswerOnlyPrecheck(ResponseCodeEnum.INVALID_ACCOUNT_ID))
                .onNetwork(
                        "NET_B",
                        QueryVerbs.getAccountBalance(acctC::get)
                                .hasAnswerOnlyPrecheck(ResponseCodeEnum.INVALID_ACCOUNT_ID));

        return builder.asDynamicTests();
    }

    @MultiNetworkHapiTest(
            networks = {
                @MultiNetworkHapiTest.Network(name = "NET_ALPHA", size = 2),
                @MultiNetworkHapiTest.Network(name = "NET_BETA", size = 2),
                @MultiNetworkHapiTest.Network(name = "NET_GAMMA", size = 2)
            })
    @DisplayName("Independent ledger counters")
    Stream<DynamicTest> independentLedgerCounters(
            final SubProcessNetwork netAlpha, final SubProcessNetwork netBeta, final SubProcessNetwork netGamma) {
        // Simple sanity: each network should be able to create its own payer without affecting the others
        final var builder = multiNetworkHapiTest(netAlpha, netBeta, netGamma)
                .onNetwork(
                        "NET_ALPHA",
                        TxnVerbs.cryptoCreate("alphaPayer").balance(ONE_HBAR),
                        QueryVerbs.getAccountBalance("alphaPayer").hasTinyBars(ONE_HBAR))
                .onNetwork(
                        "NET_BETA",
                        TxnVerbs.cryptoCreate("betaPayer").balance(ONE_HBAR),
                        QueryVerbs.getAccountBalance("betaPayer").hasTinyBars(ONE_HBAR))
                .onNetwork(
                        "NET_GAMMA",
                        TxnVerbs.cryptoCreate("gammaPayer").balance(ONE_HBAR),
                        QueryVerbs.getAccountBalance("gammaPayer").hasTinyBars(ONE_HBAR));

        return builder.asDynamicTests();
    }
}
