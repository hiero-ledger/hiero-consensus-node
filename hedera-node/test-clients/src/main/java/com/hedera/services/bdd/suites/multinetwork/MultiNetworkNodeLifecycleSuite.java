// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.multinetwork;

import static com.hedera.services.bdd.spec.HapiPropertySource.asAccountString;
import static com.hedera.services.bdd.spec.HapiSpec.multiHapiTest;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;

import com.hedera.services.bdd.junit.MultiNetworkHapiTest;
import com.hedera.services.bdd.junit.hedera.subprocess.SubProcessNetwork;
import com.hedera.services.bdd.suites.hip869.NodeCreateTest;
import com.hedera.services.bdd.spec.queries.QueryVerbs;
import com.hedera.services.bdd.spec.transactions.TxnVerbs;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;

/**
 * Exercises node delete/create against multiple networks to validate isolation and post-churn stability.
 */
public class MultiNetworkNodeLifecycleSuite {
    @MultiNetworkHapiTest(networks = {
        @MultiNetworkHapiTest.Network(name = "NET_A", size = 4),
        @MultiNetworkHapiTest.Network(name = "NET_B", size = 4),
        @MultiNetworkHapiTest.Network(name = "NET_C", size = 4)
    })
    @DisplayName("multi-network-node-delete-create")
    Stream<DynamicTest> nodeDeleteCreateAcrossThreeNetworks(
            final SubProcessNetwork netA, final SubProcessNetwork netB, final SubProcessNetwork netC) {
        // Each network deletes a different existing node ID
        final long deleteA = 1L;
        final long deleteB = 2L;
        final long deleteC = 3L;
        final var acctA = new AtomicReference<String>();
        final var acctB = new AtomicReference<String>();
        final var acctC = new AtomicReference<String>();
        final var nodeAcctA = "netA-node4-account";
        final var nodeAcctB = "netB-node4-account";
        final var nodeAcctC = "netC-node4-account";

        return multiHapiTest(netA, netB, netC)
                .on(
                        "NET_A",
                        TxnVerbs.cryptoCreate("acctA")
                                .balance(ONE_HBAR)
                                .payingWith(GENESIS)
                                .exposingCreatedIdTo(id -> acctA.set(asAccountString(id))),
                        QueryVerbs.getAccountBalance(acctA::get).hasTinyBars(ONE_HBAR),
                        TxnVerbs.nodeDelete(String.valueOf(deleteA)).payingWith(GENESIS),
                        TxnVerbs.cryptoCreate(nodeAcctA).payingWith(GENESIS),
                        TxnVerbs.nodeCreate("netA-node4", nodeAcctA)
                                .payingWith(GENESIS)
                                .description("netA-node4")
                                .serviceEndpoint(NodeCreateTest.SERVICES_ENDPOINTS_IPS)
                                .gossipEndpoint(NodeCreateTest.GOSSIP_ENDPOINTS_IPS)
                                .adminKey(GENESIS)
                                .gossipCaCertificate(encodeCert()),
                        QueryVerbs.getAccountBalance(acctA::get).hasTinyBars(ONE_HBAR),
                        QueryVerbs.getScheduleInfo("0.0." + deleteA)
                                .hasCostAnswerPrecheck(ResponseCodeEnum.INVALID_SCHEDULE_ID))
                .on(
                        "NET_B",
                        TxnVerbs.cryptoCreate("acctB")
                                .balance(ONE_HBAR)
                                .payingWith(GENESIS)
                                .exposingCreatedIdTo(id -> acctB.set(asAccountString(id))),
                        QueryVerbs.getAccountBalance(acctB::get).hasTinyBars(ONE_HBAR),
                        TxnVerbs.nodeDelete(String.valueOf(deleteB)).payingWith(GENESIS),
                        TxnVerbs.cryptoCreate(nodeAcctB).payingWith(GENESIS),
                        TxnVerbs.nodeCreate("netB-node4", nodeAcctB)
                                .payingWith(GENESIS)
                                .description("netB-node4")
                                .serviceEndpoint(NodeCreateTest.SERVICES_ENDPOINTS_IPS)
                                .gossipEndpoint(NodeCreateTest.GOSSIP_ENDPOINTS_IPS)
                                .adminKey(GENESIS)
                                .gossipCaCertificate(encodeCert()),
                        QueryVerbs.getAccountBalance(acctB::get).hasTinyBars(ONE_HBAR),
                        QueryVerbs.getScheduleInfo("0.0." + deleteB)
                                .hasCostAnswerPrecheck(ResponseCodeEnum.INVALID_SCHEDULE_ID))
                .on(
                        "NET_C",
                        TxnVerbs.cryptoCreate("acctC")
                                .balance(ONE_HBAR)
                                .payingWith(GENESIS)
                                .exposingCreatedIdTo(id -> acctC.set(asAccountString(id))),
                        QueryVerbs.getAccountBalance(acctC::get).hasTinyBars(ONE_HBAR),
                        TxnVerbs.nodeDelete(String.valueOf(deleteC)).payingWith(GENESIS),
                        TxnVerbs.cryptoCreate(nodeAcctC).payingWith(GENESIS),
                        TxnVerbs.nodeCreate("netC-node4", nodeAcctC)
                                .payingWith(GENESIS)
                                .description("netC-node4")
                                .serviceEndpoint(NodeCreateTest.SERVICES_ENDPOINTS_IPS)
                                .gossipEndpoint(NodeCreateTest.GOSSIP_ENDPOINTS_IPS)
                                .adminKey(GENESIS)
                                .gossipCaCertificate(encodeCert()),
                        QueryVerbs.getAccountBalance(acctC::get).hasTinyBars(ONE_HBAR),
                        QueryVerbs.getScheduleInfo("0.0." + deleteC)
                                .hasCostAnswerPrecheck(ResponseCodeEnum.INVALID_SCHEDULE_ID))
                .asDynamicTests("multi-network-node-delete-create");
    }

    private static byte[] encodeCert() {
        try {
            return NodeCreateTest.generateX509Certificates(1).getFirst().getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate/encode X509 certificate for node create", e);
        }
    }
}
