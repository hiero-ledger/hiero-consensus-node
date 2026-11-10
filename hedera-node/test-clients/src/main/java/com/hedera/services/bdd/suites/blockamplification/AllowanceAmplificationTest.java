// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.blockamplification;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoApproveAllowance;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingThrottles;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepForSeconds;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.blockamplification.BlockAmplificationSupport.ATTACKER;
import static com.hedera.services.bdd.suites.blockamplification.BlockAmplificationSupport.MAINNET_THROTTLES_WITH_HV;
import static com.hedera.services.bdd.suites.blockamplification.BlockAmplificationSupport.buildCryptoAllowanceList;
import static com.hedera.services.bdd.suites.blockamplification.BlockAmplificationSupport.floodAssertingBudgetRejection;

import com.hedera.services.bdd.GenesisSubprocessTest;
import com.hedera.services.bdd.GenesisSubprocessTest.SubProcessNodeConfig;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;

/**
 * Proof of concept for the <b>CryptoApproveAllowance</b> block-stream amplification vector — the mainnet-allowance case
 * from the original analysis.
 *
 * <p>An account's crypto, token and NFT allowances are stored as repeated fields on the {@code Account}. PR #27718
 * replaces a changed <em>repeated</em> field whole, so touching a single allowance re-emits the account's <em>entire</em>
 * allowance list as a state change (measured ~2,825 bytes for a 100-entry list, see {@code .context} reports). Once an
 * owner has accumulated a large list, every further one-entry approval — priced as a single {@code CryptoApproveAllowance}
 * — re-emits the whole list. Flooding such approvals fills the block, after which non-exempt transactions are rejected
 * with {@code THROTTLED_AT_CONSENSUS}.
 *
 * <p>The list here is built to {@value #LIST_SIZE} entries with throttle-exempt (GENESIS-paid) setup approvals, so the
 * attacker's own approvals are the only budget-eligible transactions. {@code CryptoApproveAllowance} is a HIP-1313
 * high-volume functionality (HVCryptoApproveAllowanceThrottles), so the second proof marks the approvals high-volume,
 * raising the lane from the 10,500 ops/s shared throughput bucket to a dedicated 10,000 ops/s high-volume bucket.
 *
 * <p>The structural fix evaluated in the analysis — storing allowances as a house-style doubly-linked list so a single
 * approval touches only its own entry instead of the whole list — is what collapses this re-emit from kilobytes to
 * ~85 bytes per touch; this proof is the "before".
 */
@OrderedInIsolation
@SuppressWarnings("java:S2699") // assertions live in floodAssertingBudgetRejection
public class AllowanceAmplificationTest {
    private static final int LIST_SIZE = 40;
    private static final String SPENDER_PREFIX = "allowanceSpender";
    private static final long PER_ALLOWANCE = 1_000_000L;
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
    final Stream<DynamicTest> approveAllowanceFloodsBlockOnNormalLane() {
        return hapiTest(
                overridingThrottles(MAINNET_THROTTLES_WITH_HV),
                cryptoCreate(ATTACKER).balance(ONE_MILLION_HBARS),
                buildCryptoAllowanceList(ATTACKER, SPENDER_PREFIX, LIST_SIZE, PER_ALLOWANCE),
                sleepForSeconds(11),
                floodAssertingBudgetRejection(
                        "approveAllowance-normal",
                        ATTACK_ATTEMPTS,
                        // Re-approving one spender with a changing amount re-emits the whole repeated allowance field
                        i -> cryptoApproveAllowance()
                                .addCryptoAllowance(ATTACKER, SPENDER_PREFIX + "0", PER_ALLOWANCE + 1L + i)
                                .payingWith(ATTACKER)
                                .noLogging()));
    }

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
    final Stream<DynamicTest> approveAllowanceFloodsBlockOnHighVolumeLane() {
        return hapiTest(
                overridingThrottles(MAINNET_THROTTLES_WITH_HV),
                cryptoCreate(ATTACKER).balance(ONE_MILLION_HBARS),
                buildCryptoAllowanceList(ATTACKER, SPENDER_PREFIX, LIST_SIZE, PER_ALLOWANCE),
                sleepForSeconds(11),
                floodAssertingBudgetRejection(
                        "approveAllowance-highVolume",
                        ATTACK_ATTEMPTS,
                        i -> cryptoApproveAllowance()
                                .addCryptoAllowance(ATTACKER, SPENDER_PREFIX + "0", PER_ALLOWANCE + 1L + i)
                                .payingWith(ATTACKER)
                                .withHighVolume()
                                .noLogging()));
    }
}
