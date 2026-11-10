// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.blockamplification;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.updateTopic;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingThrottles;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepForSeconds;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.blockamplification.BlockAmplificationSupport.ATTACKER;
import static com.hedera.services.bdd.suites.blockamplification.BlockAmplificationSupport.MAINNET_THROTTLES_WITH_HV;
import static com.hedera.services.bdd.suites.blockamplification.BlockAmplificationSupport.floodAssertingBudgetRejection;

import com.hedera.services.bdd.GenesisSubprocessTest;
import com.hedera.services.bdd.GenesisSubprocessTest.SubProcessNodeConfig;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;

/**
 * Proof of concept for the <b>topic / token entity</b> amplification vector (catalog entry: topic/token key and fee
 * lists re-emitted whole).
 *
 * <p>An update to a topic or token re-emits the whole entity as a state change (its keys, memo and — for tokens — its
 * custom-fee list). PR #27718's partial diff helps a single-field change, but repeated and nested fields (key lists,
 * fee lists) are still replaced whole, so flooding updates fills the block with re-emitted entities. These are
 * input-proportional, lower-severity vectors; the mitigation is per-entry byte caps on the key and fee lists.
 *
 * <p>Both proofs flood updates (topic memo, token memo) paid by the attacker until the small {@value #BUDGET_BYTES}-byte
 * budget is crossed and further updates are rejected {@code THROTTLED_AT_CONSENSUS}.
 *
 * <p><b>High-volume note:</b> {@code ConsensusUpdateTopic} and {@code TokenUpdate} are not HIP-1313 high-volume
 * functionalities, so there is no high-volume variant.
 */
@OrderedInIsolation
@SuppressWarnings("java:S2699") // assertions live in floodAssertingBudgetRejection
public class TopicTokenListAmplificationTest {
    private static final int BUDGET_BYTES = 4_096; // 4 KiB
    private static final String TOPIC = "amplificationUpdTopic";
    private static final String TOKEN = "amplificationUpdToken";
    private static final String ADMIN = "amplificationAdminKey";
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
                            "blockStream.maxBlockExecutionSizeBytes", "4096",
                            "blockStream.maxBlockSizeLimitEnabled", "true",
                            "blockStream.blockPeriod", "10s",
                            "networkAdmin.highVolumeThrottlesEnabled", "true"
                        })
            })
    final Stream<DynamicTest> topicUpdatesFloodBlock() {
        return hapiTest(
                overridingThrottles(MAINNET_THROTTLES_WITH_HV),
                newKeyNamed(ADMIN),
                cryptoCreate(ATTACKER).balance(ONE_MILLION_HBARS).key(ADMIN),
                createTopic(TOPIC).adminKeyName(ADMIN).submitKeyName(ADMIN).payingWith(GENESIS),
                sleepForSeconds(11),
                floodAssertingBudgetRejection(
                        "topicUpdate",
                        ATTACK_ATTEMPTS,
                        // Each update re-emits the whole topic entity (admin key, submit key, memo)
                        i -> updateTopic(TOPIC)
                                .topicMemo("amplification-memo-" + i)
                                .payingWith(ATTACKER)
                                .signedBy(ATTACKER)
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
                            "blockStream.maxBlockExecutionSizeBytes", "4096",
                            "blockStream.maxBlockSizeLimitEnabled", "true",
                            "blockStream.blockPeriod", "10s",
                            "networkAdmin.highVolumeThrottlesEnabled", "true"
                        })
            })
    final Stream<DynamicTest> tokenUpdatesFloodBlock() {
        return hapiTest(
                overridingThrottles(MAINNET_THROTTLES_WITH_HV),
                newKeyNamed(ADMIN),
                cryptoCreate(ATTACKER).balance(ONE_MILLION_HBARS).key(ADMIN),
                tokenCreate(TOKEN).adminKey(ADMIN).treasury(ATTACKER).payingWith(GENESIS),
                sleepForSeconds(11),
                floodAssertingBudgetRejection(
                        "tokenUpdate",
                        ATTACK_ATTEMPTS,
                        // Each update re-emits the whole token entity (keys, memo, fee schedule)
                        i -> tokenUpdate(TOKEN)
                                .entityMemo("amplification-memo-" + i)
                                .payingWith(ATTACKER)
                                .signedBy(ATTACKER)
                                .noLogging()));
    }
}
