// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.misc;

import static com.hedera.services.bdd.junit.TestTags.ISS;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.freezeOnly;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitForFrozenNetwork;
import static com.hedera.services.bdd.suites.regression.system.LifecycleTest.FREEZE_TIMEOUT;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.IssHapiTest;
import com.hedera.services.bdd.junit.hedera.NodeSelector;
import com.hedera.services.bdd.spec.transactions.token.TokenMovement;
import com.hedera.services.bdd.spec.utilops.UtilVerbs;
import com.hedera.services.bdd.suites.crypto.ParseableIssBlockStreamValidationOp;
import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;

@Tag(ISS)
@IssHapiTest
@Order(Integer.MAX_VALUE - 3)
// This test requires a specific network configuration to run in CI. We will enable it when we can get it running as a
// separate PR check
@Disabled
class IssHandlingTestSuite {
    private static final long NODE_0_ACCT_ID = 3; // The ISS node
    private static final long NODE_1_ACCT_ID = 4; // One of the Non-ISS nodes
    private static final String NODE_1_FULL_ACCT_ID = "0.0." + NODE_1_ACCT_ID;

    @HapiTest
    final Stream<DynamicTest> simulateIss() {
        final var node0Selector = NodeSelector.byOperatorAccountId(
                AccountID.newBuilder().accountNum(NODE_0_ACCT_ID).build());
        return hapiTest(
                // Log Preconditions: Make sure the network is configured to simulate an ISS
                UtilVerbs.assertHgcaaLogContains(node0Selector, "ledger.transfers.maxLen = 5", Duration.ofSeconds(10)),
                UtilVerbs.assertHgcaaLogContains(
                        NodeSelector.byName("node2"), "ledger.transfers.maxLen = 3", Duration.ofSeconds(10)),
                newKeyNamed("key1"),
                newKeyNamed("key2"),
                newKeyNamed("key3"),
                newKeyNamed("key4"),
                // Create accounts with one of the non-ISS nodes to ensure we don't get an ISS before we expect it
                cryptoCreate("civilian1").balance(1000000L).key("key1").setNode(NODE_1_FULL_ACCT_ID),
                cryptoCreate("civilian2").balance(100L).key("key2").setNode(NODE_1_FULL_ACCT_ID),
                cryptoCreate("civilian3").balance(100L).key("key3").setNode(NODE_1_FULL_ACCT_ID),
                cryptoCreate("civilian4").balance(100L).key("key4").setNode(NODE_1_FULL_ACCT_ID),
                // The explicit transfers are specified here, but others will be calculated and included by the
                // node, thus putting the total number of transfers over the _network's_ configured limit, but not
                // over the ISS node's configured limit
                cryptoTransfer(
                                TokenMovement.movingHbar(1).between("civilian1", "civilian4"),
                                TokenMovement.movingHbar(1).between("civilian2", "civilian4"),
                                TokenMovement.movingHbar(1).between("civilian3", "civilian4"))
                        .signedBy("key1", "key2", "key3")
                        .payingWith("civilian1")
                        // Intentionally submit this transfer to the modified node
                        .setNode("0.0." + NODE_0_ACCT_ID)
                        // The ISS node will shut down its GRPC server after detecting the ISS, so we need to allow for
                        // any status type
                        .hasAnyStatusAtAll(),
                // Verify we actually got an ISS
                UtilVerbs.assertHgcaaLogContains(node0Selector, "ISS detected", Duration.ofSeconds(60)),
                // Verify the block stream manager completed its fatal shutdown process
                UtilVerbs.assertHgcaaLogContains(
                        node0Selector, "Block stream fatal shutdown complete", Duration.ofSeconds(30)),
                // Submit the freeze to one of the non-ISS nodes
                freezeOnly().startingIn(2).seconds().setNode(NODE_1_FULL_ACCT_ID),
                waitForFrozenNetwork(FREEZE_TIMEOUT, NodeSelector.exceptNodeIds(0)),
                // Wait for any block stream files to close
                sleepFor(2000),
                new ParseableIssBlockStreamValidationOp());
    }
}
