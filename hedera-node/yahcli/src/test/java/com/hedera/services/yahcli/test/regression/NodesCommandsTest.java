// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test.regression;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcingContextual;
import static com.hedera.services.yahcli.test.YahcliTestBase.REGRESSION;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.asYcDefaultNetworkKey;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.loadResourceFile;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.newNodeCapturer;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.yahcliNodes;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.spec.keys.SigControl;
import com.hedera.services.bdd.spec.utilops.ContextualActionOp;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(REGRESSION)
public class NodesCommandsTest {

    private int uniqueAdminKeyCounter = 0;

    @HapiTest
    final Stream<DynamicTest> basicNodeCommandsTest() {
        final var newNodeNum = new AtomicLong();
        final var adminKey = getUniqueAdminKey();
        final var adminKeyFileName = adminKey + ".pem";
        final var certFilePath = loadResourceFile("testFiles/s-public-node1.pem");
        return hapiTest(
                newKeyNamed(adminKey)
                        .shape(SigControl.ED25519_ON)
                        .exportingTo(() -> asYcDefaultNetworkKey(adminKeyFileName), "keypass"),
                doingContextual(spec -> allRunFor(
                        spec,
                        yahcliNodes(
                                        "create",
                                        "-a",
                                        "23",
                                        "-d",
                                        "Test node",
                                        "-k",
                                        asYcDefaultNetworkKey(adminKeyFileName),
                                        // We are using the full option name here, as -c overrides the config location
                                        "--gossipCaCertificate",
                                        certFilePath.toString(),
                                        "-h",
                                        certFilePath.toString(),
                                        "-g",
                                        "127.0.0.1:50211",
                                        "-s",
                                        "a.b.com:50212")
                                .exposingOutputTo(newNodeCapturer(newNodeNum::set)),
                        // TODO: add state validation
                        // Update the just created node
                        sourcingContextual(spec1 -> yahcliNodes(
                                        "update",
                                        "-n",
                                        Long.toString(newNodeNum.get()),
                                        "-k",
                                        asYcDefaultNetworkKey(adminKeyFileName),
                                        "-d",
                                        "Updated test node")
                                .exposingOutputTo(output ->
                                        assertTrue(output.contains("node" + newNodeNum.get() + " has been updated")))),
                        // Finally delete the just created node
                        sourcingContextual(spec2 -> yahcliNodes("delete", "-n", Long.toString(newNodeNum.get()))
                                .exposingOutputTo(output -> assertTrue(
                                        output.contains("node" + newNodeNum.get() + " has been deleted")))))));
    }

    @LeakyHapiTest(overrides = {"nodes.updateAccountIdAllowed"})
    final Stream<DynamicTest> updateNodeAccountIdCommandTest() {
        final var newNodeNum = new AtomicLong();
        final var zeroBalanceAccNum = new AtomicLong();
        final var newAccNum = new AtomicLong();

        final var adminKey = getUniqueAdminKey();
        final var adminKeyFileName = adminKey + ".pem";

        final var zeroBalanceAccount = "zeroBalanceAccount";
        final var newAccount = "newAccount";

        final var certFilePath = loadResourceFile("testFiles/s-public-node1.pem");
        return hapiTest(
                overriding("nodes.updateAccountIdAllowed", "true"),
                newKeyNamed(adminKey)
                        .shape(SigControl.ED25519_ON)
                        .exportingTo(() -> asYcDefaultNetworkKey(adminKeyFileName), "keypass"),
                // create new account with 0 balance
                cryptoCreate(zeroBalanceAccount)
                        .balance(0L)
                        .exposingCreatedIdTo(id -> zeroBalanceAccNum.set(id.getAccountNum())),
                saveAccountKeyToFile(zeroBalanceAccount),
                // create account with positive balance
                cryptoCreate(newAccount)
                        .balance(100_000_000L)
                        .exposingCreatedIdTo(id -> newAccNum.set(id.getAccountNum())),
                saveAccountKeyToFile(newAccount),

                // Create new node
                doingContextual(spec -> allRunFor(
                        spec,
                        yahcliNodes(
                                        "create",
                                        "-a",
                                        "23",
                                        "-d",
                                        "Test node",
                                        "-k",
                                        asYcDefaultNetworkKey(adminKeyFileName),
                                        // We are using the full option name here, as -c overrides the config location
                                        "--gossipCaCertificate",
                                        certFilePath.toString(),
                                        "-h",
                                        certFilePath.toString(),
                                        "-g",
                                        "127.0.0.1:50211",
                                        "-s",
                                        "a.b.com:50212")
                                .exposingOutputTo(newNodeCapturer(newNodeNum::set)))),

                // Update the node
                doingContextual(spec -> allRunFor(
                        spec,
                        // Try to update node accountId with 0 balance
                        yahcliNodes(
                                        "update",
                                        "-n",
                                        Long.toString(newNodeNum.get()),
                                        "-a",
                                        Long.toString(zeroBalanceAccNum.get()),
                                        "-k",
                                        asYcDefaultNetworkKey(adminKeyFileName),
                                        "-d",
                                        "Updated test node with O balance account id should fail")
                                .expectFail()
                                .exposingOutputTo(output -> {
                                    assertTrue(output.contains("FAILED to update node" + newNodeNum.get()));
                                }),
                        // Update the node with accountId with positive balance
                        yahcliNodes(
                                        "update",
                                        "-n",
                                        Long.toString(newNodeNum.get()),
                                        "-a",
                                        Long.toString(newAccNum.get()),
                                        "-k",
                                        asYcDefaultNetworkKey(adminKeyFileName),
                                        "-d",
                                        "Update node with positive balance account id")
                                .exposingOutputTo(output ->
                                        assertTrue(output.contains("node" + newNodeNum.get() + " has been updated"))),

                        // Finally delete the just created node
                        yahcliNodes("delete", "-n", Long.toString(newNodeNum.get()))
                                .exposingOutputTo(output -> assertTrue(
                                        output.contains("node" + newNodeNum.get() + " has been deleted"))))));
    }

    // Helpers

    private String getUniqueAdminKey() {
        uniqueAdminKeyCounter++;
        return "adminKey_" + uniqueAdminKeyCounter;
    }

    private String getAccountKeyFileName(Long accountNum) {
        return "account" + accountNum + ".pem";
    }

    private ContextualActionOp saveAccountKeyToFile(String account) {
        final var accountKey = account + "Key";

        return doingContextual(spec -> {
            final var accountId = spec.registry().getAccountID(account);
            final var accountKeyFileName = getAccountKeyFileName(accountId.getAccountNum());
            allRunFor(
                    spec,
                    // create new key and export it to file
                    newKeyNamed(accountKey)
                            .shape(SigControl.ED25519_ON)
                            .exportingTo(() -> asYcDefaultNetworkKey(accountKeyFileName), "keypass"),
                    cryptoUpdate(account).key(accountKey));
        });
    }
}
