// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.multinetwork;

import static com.hedera.services.bdd.spec.HapiPropertySource.asAccountString;
import static com.hedera.services.bdd.spec.HapiSpec.multiNetworkHapiTest;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;

import com.hedera.services.bdd.junit.MultiNetworkHapiTest;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.junit.TestTags;
import com.hedera.services.bdd.junit.hedera.subprocess.SubProcessNetwork;
import com.hedera.services.bdd.spec.queries.QueryVerbs;
import com.hedera.services.bdd.spec.transactions.TxnVerbs;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Test suite for multi-network isolation testing.
 * <p>
 * Verifies that multiple Hedera networks running in parallel remain isolated,
 * ensuring that operations on one network do not affect the state or behavior
 * of other networks. Includes tests for account creation, balance queries,
 * and independent ledger counters across distinct networks.
 */
@OrderedInIsolation
@Tag(TestTags.MULTINETWORK)
public class MultiNetworkIsolationSuite {
    @MultiNetworkHapiTest(
            networks = {
                @MultiNetworkHapiTest.Network(name = "NET_A", size = 1, firstGrpcPort = 27400),
                @MultiNetworkHapiTest.Network(name = "NET_B", size = 1, firstGrpcPort = 28000),
                @MultiNetworkHapiTest.Network(name = "NET_C", size = 1, shard = 21, realm = 22, firstGrpcPort = 28600)
            })
    @DisplayName("Three network isolation")
    Stream<DynamicTest> threeNetworkIsolation(
            final SubProcessNetwork netA, final SubProcessNetwork netB, final SubProcessNetwork netC) {
        final var acctA = new AtomicReference<String>();
        final var acctB = new AtomicReference<String>();
        final var acctC = new AtomicReference<String>();
        final long balA = ONE_HBAR;
        final long balB = 2 * ONE_HBAR;
        final long balC = 3 * ONE_HBAR;

        final var builder = multiNetworkHapiTest(netA, netB, netC)
                .onNetwork(
                        "NET_A",
                        TxnVerbs.cryptoCreate("acctA")
                                .balance(balA)
                                .exposingCreatedIdTo(id -> acctA.set(asAccountString(id))),
                        QueryVerbs.getAccountBalance(acctA::get).hasTinyBars(balA))
                .onNetwork(
                        "NET_B",
                        TxnVerbs.cryptoCreate("acctB")
                                .balance(balB)
                                .exposingCreatedIdTo(id -> acctB.set(asAccountString(id))),
                        QueryVerbs.getAccountBalance(acctB::get).hasTinyBars(balB))
                .onNetwork(
                        "NET_C",
                        TxnVerbs.cryptoCreate("acctC")
                                .balance(balC)
                                .exposingCreatedIdTo(id -> acctC.set(asAccountString(id))),
                        QueryVerbs.getAccountBalance(acctC::get).hasTinyBars(balC))
                // Even with the same shard/realm and colliding account numbers, each network
                // should return its own balance for the "foreign" account id.
                .onNetwork(
                        "NET_A",
                        // NET_A and NET_B share shard/realm; querying acctB resolves to NET_A's local account id
                        QueryVerbs.getAccountBalance(acctB::get).hasTinyBars(balA),
                        // NET_C uses different shard/realm, so its id should be invalid here
                        QueryVerbs.getAccountBalance(acctC::get)
                                .hasAnswerOnlyPrecheck(ResponseCodeEnum.INVALID_ACCOUNT_ID))
                .onNetwork(
                        "NET_B",
                        // NET_A id collides with NET_B's local first account
                        QueryVerbs.getAccountBalance(acctA::get).hasTinyBars(balB),
                        // NET_C shard/realm differs so expect invalid
                        QueryVerbs.getAccountBalance(acctC::get)
                                .hasAnswerOnlyPrecheck(ResponseCodeEnum.INVALID_ACCOUNT_ID))
                .onNetwork(
                        "NET_C",
                        // NET_C shard/realm differs from NET_A/NET_B; cross-network ids must be invalid
                        QueryVerbs.getAccountBalance(acctA::get)
                                .hasAnswerOnlyPrecheck(ResponseCodeEnum.INVALID_ACCOUNT_ID),
                        QueryVerbs.getAccountBalance(acctB::get)
                                .hasAnswerOnlyPrecheck(ResponseCodeEnum.INVALID_ACCOUNT_ID));

        return builder.asDynamicTests();
    }

    @MultiNetworkHapiTest(
            networks = {
                @MultiNetworkHapiTest.Network(name = "NET_ALPHA", size = 1),
                @MultiNetworkHapiTest.Network(name = "NET_BETA", size = 1),
                @MultiNetworkHapiTest.Network(name = "NET_GAMMA", size = 1)
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
