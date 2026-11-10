// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.blockamplification;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoApproveAllowance;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.allowanceTinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingThrottles;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepForSeconds;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.blockamplification.BlockAmplificationSupport.ATTACKER;
import static com.hedera.services.bdd.suites.blockamplification.BlockAmplificationSupport.MAINNET_THROTTLES_WITH_HV;
import static com.hedera.services.bdd.suites.blockamplification.BlockAmplificationSupport.RECEIVER;
import static com.hedera.services.bdd.suites.blockamplification.BlockAmplificationSupport.buildCryptoAllowanceList;
import static com.hedera.services.bdd.suites.blockamplification.BlockAmplificationSupport.floodAssertingBudgetRejection;

import com.hedera.services.bdd.GenesisSubprocessTest;
import com.hedera.services.bdd.GenesisSubprocessTest.SubProcessNodeConfig;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;

/**
 * Proof of concept for the <b>approved-spend</b> block-stream amplification vector — the cheapest bytes in the allowance
 * family, and the one highlighted in the mainnet-allowance analysis (the approved FT transfer gives ~45% of the
 * allowance output at ~0.18% of the fee).
 *
 * <p>When a spender debits an owner via an {@code is_approval} transfer, the owner's allowance to that spender is
 * decremented, which under PR #27718 re-emits the owner's <em>entire</em> allowance list (a changed repeated field is
 * replaced whole). Crucially the <em>owner never signs</em> — the spender triggers and pays for the re-emit of the
 * owner's whole list. So an attacker who holds an allowance from an owner that has a large list can re-emit that whole
 * list on every cheap approved transfer.
 *
 * <p>Here the owner ({@code ATTACKER}) is given a {@value #LIST_SIZE}-entry list plus an hbar allowance to the spender,
 * all via throttle-exempt (GENESIS-paid) setup. The spender then floods tiny approved transfers, each re-emitting the
 * owner's whole list, until the {@value #BUDGET_BYTES}-byte budget is crossed and further transfers are rejected
 * {@code THROTTLED_AT_CONSENSUS}.
 *
 * <p>The structural fix evaluated in the analysis is a house-style doubly-linked allowance list, which is the only fix
 * that closes this path (an owner-count limit on {@code CryptoApproveAllowance} does not, because the spend path does not
 * go through {@code CryptoApproveAllowance} at all).
 *
 * <p><b>High-volume note:</b> a plain {@code CryptoTransfer} only takes a high-volume lane when it does implicit account
 * creation, which an approved debit to an existing receiver does not; so there is no high-volume variant for this path.
 */
@OrderedInIsolation
@SuppressWarnings("java:S2699") // assertions live in floodAssertingBudgetRejection
public class ApprovedSpendAmplificationTest {
    private static final int BUDGET_BYTES = 8_192; // 8 KiB
    private static final int LIST_SIZE = 40;
    private static final String SPENDER_PREFIX = "approvedSpendSpender";
    private static final String SPENDER = "approvedSpender";
    private static final long PER_ALLOWANCE = 1_000_000_000L;
    private static final int ATTACK_ATTEMPTS = 16;

    @HapiTest
    @GenesisSubprocessTest(
            networkSize = 1,
            subProcessNodeConfigs = {
                @SubProcessNodeConfig(
                        nodeId = 0,
                        applicationPropertiesOverrides = {
                            "blockStream.streamMode", "BLOCKS",
                            "blockStream.writerMode", "FILE",
                            "blockStream.enableStateChangeDeltas", "true",
                            "blockStream.stateChangeDeltaMinSaving", "16",
                            "blockStream.maxBlockExecutionSizeBytes", "8192",
                            "blockStream.maxBlockSizeLimitEnabled", "true",
                            "blockStream.blockPeriod", "10s",
                            "networkAdmin.highVolumeThrottlesEnabled", "true"
                        })
            })
    final Stream<DynamicTest> approvedSpendFloodsBlock() {
        return hapiTest(
                overridingThrottles(MAINNET_THROTTLES_WITH_HV),
                cryptoCreate(ATTACKER).balance(ONE_MILLION_HBARS),
                cryptoCreate(SPENDER).balance(ONE_MILLION_HBARS),
                cryptoCreate(RECEIVER).balance(0L),
                buildCryptoAllowanceList(ATTACKER, SPENDER_PREFIX, LIST_SIZE, PER_ALLOWANCE),
                // Give the spender an hbar allowance against the owner (owner signs, GENESIS pays -> budget-exempt)
                cryptoApproveAllowance()
                        .addCryptoAllowance(ATTACKER, SPENDER, PER_ALLOWANCE)
                        .payingWith(GENESIS)
                        .signedBy(GENESIS, ATTACKER),
                sleepForSeconds(11),
                floodAssertingBudgetRejection(
                        "approvedSpend",
                        ATTACK_ATTEMPTS,
                        // The spender pays; the owner never signs; the owner's whole list is re-emitted each time
                        i -> cryptoTransfer(allowanceTinyBarsFromTo(ATTACKER, RECEIVER, 1L + i))
                                .payingWith(SPENDER)
                                .noLogging()));
    }
}
