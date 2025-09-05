// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test.scenarios;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcingContextual;
import static com.hedera.services.yahcli.test.YahcliTestBase.REGRESSION;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.asYcDefaultNetworkKey;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.loadResourceFile;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.newNodeCapturer;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.yahcliNodes;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.keys.SigControl;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(REGRESSION)
public class NodesCommandsTest {

    @HapiTest
    final Stream<DynamicTest> basicNodeCommandsTest() {
        final var newNodeNum = new AtomicLong();
        final var adminKey = "adminKey";
        final var adminKeyFileName = adminKey + ".pem";
        final var certFilePath = loadResourceFile("s-public-node1.pem");
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
}
