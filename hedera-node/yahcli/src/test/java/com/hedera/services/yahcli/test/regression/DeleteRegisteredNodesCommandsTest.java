// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test.regression;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcingContextual;
import static com.hedera.services.yahcli.test.YahcliTestBase.REGRESSION;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.asYcDefaultNetworkKey;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.loadResourceFile;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.newNodeCapturer;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.newRegisteredNodeCapturer;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.yahcliNodes;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.yahcliRegisteredNodes;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.spec.keys.SigControl;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Regression tests for the {@code yahcli registeredNodes delete} command.
 *
 * <p>Each test creates a registered node and then deletes it with a specific
 * combination of options, asserting that the command succeeds or fails as expected.
 * Tests require a running subprocess network; run via {@code ./gradlew :yahcli:testSubprocess}.
 *
 * @see <a href="https://hips.hedera.com/hip/hip-1137">HIP-1137</a>
 */
@Tag(REGRESSION)
public class DeleteRegisteredNodesCommandsTest {

    /**
     * Creates a registered node with an ED25519 admin key, then deletes it using only
     * the payer (treasury) signature — no admin key supplied. Verifies that treasury
     * authority alone is sufficient for deletion.
     */
    @HapiTest
    final Stream<DynamicTest> deleteRegisteredNodeWithPayerOnly() {
        final var createdId = new AtomicLong();
        final var adminKeyFile = "rn_del_payer_only.pem";
        return hapiTest(
                newKeyNamed("adminKey")
                        .shape(SigControl.ED25519_ON)
                        .exportingTo(() -> asYcDefaultNetworkKey(adminKeyFile), "keypass"),
                doingContextual(spec -> allRunFor(
                        spec,
                        yahcliRegisteredNodes(
                                        "create",
                                        "-k",
                                        asYcDefaultNetworkKey(adminKeyFile),
                                        "--blockNodeEndpoint",
                                        "127.0.0.1:8080")
                                .exposingOutputTo(newRegisteredNodeCapturer(createdId::set)))),
                sourcingContextual(spec -> yahcliRegisteredNodes("delete", "-n", Long.toString(createdId.get()))
                        .exposingOutputTo(output -> assertTrue(
                                output.contains("registeredNode" + createdId.get() + " has been deleted")))));
    }

    /**
     * Creates a registered node with an ED25519 admin key, then deletes it by supplying
     * that admin key. Verifies that signing with the node's admin key authorizes deletion.
     */
    @HapiTest
    final Stream<DynamicTest> deleteRegisteredNodeWithAdminKey() {
        final var createdId = new AtomicLong();
        final var adminKeyFile = "rn_del_admin_key.pem";
        return hapiTest(
                newKeyNamed("adminKey")
                        .shape(SigControl.ED25519_ON)
                        .exportingTo(() -> asYcDefaultNetworkKey(adminKeyFile), "keypass"),
                doingContextual(spec -> allRunFor(
                        spec,
                        yahcliRegisteredNodes(
                                        "create",
                                        "-k",
                                        asYcDefaultNetworkKey(adminKeyFile),
                                        "--blockNodeEndpoint",
                                        "127.0.0.1:8080")
                                .exposingOutputTo(newRegisteredNodeCapturer(createdId::set)))),
                sourcingContextual(spec -> yahcliRegisteredNodes(
                                "delete",
                                "-n",
                                Long.toString(createdId.get()),
                                "-k",
                                asYcDefaultNetworkKey(adminKeyFile))
                        .exposingOutputTo(output -> assertTrue(
                                output.contains("registeredNode" + createdId.get() + " has been deleted")))));
    }

    /**
     * Creates a registered node with a SECP256K1 admin key, then deletes it using that
     * same key. Verifies that yahcli correctly loads an ECDSA PEM file and signs a
     * delete transaction with it.
     */
    @HapiTest
    final Stream<DynamicTest> deleteWithSecp256k1AdminKey() {
        final var createdId = new AtomicLong();
        final var adminKeyFile = "rn_del_secp256k1.pem";
        return hapiTest(
                newKeyNamed("adminKey")
                        .shape(SigControl.SECP256K1_ON)
                        .exportingTo(() -> asYcDefaultNetworkKey(adminKeyFile), "keypass"),
                doingContextual(spec -> allRunFor(
                        spec,
                        yahcliRegisteredNodes(
                                        "create",
                                        "-k",
                                        asYcDefaultNetworkKey(adminKeyFile),
                                        "--blockNodeEndpoint",
                                        "127.0.0.1:8080",
                                        "-d",
                                        "Node with SECP256K1 admin key")
                                .exposingOutputTo(newRegisteredNodeCapturer(createdId::set)))),
                sourcingContextual(spec -> yahcliRegisteredNodes(
                                "delete",
                                "-n",
                                Long.toString(createdId.get()),
                                "-k",
                                asYcDefaultNetworkKey(adminKeyFile))
                        .exposingOutputTo(output -> assertTrue(
                                output.contains("registeredNode" + createdId.get() + " has been deleted")))));
    }

    /**
     * Uses the shard.realm.num dotted format for {@code -n} (e.g. {@code 0.0.5}) instead
     * of a plain number. Verifies that {@code normalizePossibleIdLiteral} correctly strips
     * the shard and realm prefix in the delete command code path.
     */
    @HapiTest
    final Stream<DynamicTest> deleteNodeIdInDottedFormat() {
        final var createdId = new AtomicLong();
        final var adminKeyFile = "rn_del_dotted_id.pem";
        return hapiTest(
                newKeyNamed("adminKey")
                        .shape(SigControl.ED25519_ON)
                        .exportingTo(() -> asYcDefaultNetworkKey(adminKeyFile), "keypass"),
                doingContextual(spec -> allRunFor(
                        spec,
                        yahcliRegisteredNodes(
                                        "create",
                                        "-k",
                                        asYcDefaultNetworkKey(adminKeyFile),
                                        "--blockNodeEndpoint",
                                        "127.0.0.1:8080")
                                .exposingOutputTo(newRegisteredNodeCapturer(createdId::set)))),
                // Use dotted 0.0.<id> format — normalizePossibleIdLiteral must strip the prefix
                sourcingContextual(spec -> yahcliRegisteredNodes(
                                "delete", "-n", "0.0." + createdId.get(), "-k", asYcDefaultNetworkKey(adminKeyFile))
                        .exposingOutputTo(output -> assertTrue(
                                output.contains("registeredNode" + createdId.get() + " has been deleted")))));
    }

    /**
     * Creates a registered node, deletes it, then attempts to delete the same ID again.
     * The second delete must fail because the node no longer exists in state.
     *
     * <p>Note: "wrong admin key" is not a meaningful negative test here because
     * {@code RegisteredNodeDeleteHandler} only requires the admin key when the payer is
     * not treasury/systemAdmin/addressBookAdmin. The test payer is always treasury, so
     * it has override authority regardless of any supplied admin key.
     */
    @HapiTest
    final Stream<DynamicTest> doubleDeleteFails() {
        final var createdId = new AtomicLong();
        final var adminKeyFile = "rn_del_double.pem";
        return hapiTest(
                newKeyNamed("adminKey")
                        .shape(SigControl.ED25519_ON)
                        .exportingTo(() -> asYcDefaultNetworkKey(adminKeyFile), "keypass"),
                doingContextual(spec -> allRunFor(
                        spec,
                        yahcliRegisteredNodes(
                                        "create",
                                        "-k",
                                        asYcDefaultNetworkKey(adminKeyFile),
                                        "--blockNodeEndpoint",
                                        "127.0.0.1:8080")
                                .exposingOutputTo(newRegisteredNodeCapturer(createdId::set)))),
                // First delete — must succeed
                sourcingContextual(spec -> yahcliRegisteredNodes("delete", "-n", Long.toString(createdId.get()))
                        .exposingOutputTo(output ->
                                assertTrue(output.contains("registeredNode" + createdId.get() + " has been deleted")))),
                // Second delete of the same ID — must fail
                sourcingContextual(spec -> yahcliRegisteredNodes("delete", "-n", Long.toString(createdId.get()))
                        .expectFail()
                        .exposingOutputTo(output ->
                                assertTrue(output.contains("FAILED to delete registeredNode" + createdId.get())))));
    }

    /**
     * Verifies that {@code REGISTERED_NODE_STILL_REFERENCED} is returned when attempting to
     * delete a registered node that is still associated with a live DAB (consensus) node.
     *
     * <p>The correct workflow is to delete (or update) the referencing DAB node first so the
     * association is cleared, after which the registered node can be deleted successfully.
     */
    @LeakyHapiTest
    final Stream<DynamicTest> deleteReferencedRegisteredNodeFails() {
        final var rnId = new AtomicLong();
        final var dabNodeId = new AtomicLong();
        final var rnAdminKeyFile = "del_rn_referenced_rn.pem";
        final var dabAdminKeyFile = "del_rn_referenced_dab.pem";
        final var certFilePath = loadResourceFile("testFiles/s-public-node1.pem");
        return hapiTest(
                newKeyNamed("rnAdminKey")
                        .shape(SigControl.ED25519_ON)
                        .exportingTo(() -> asYcDefaultNetworkKey(rnAdminKeyFile), "keypass"),
                newKeyNamed("dabAdminKey")
                        .shape(SigControl.ED25519_ON)
                        .exportingTo(() -> asYcDefaultNetworkKey(dabAdminKeyFile), "keypass"),
                doingContextual(spec -> allRunFor(
                        spec,
                        // Create the registered node
                        yahcliRegisteredNodes(
                                        "create",
                                        "-k",
                                        asYcDefaultNetworkKey(rnAdminKeyFile),
                                        "--blockNodeEndpoint",
                                        "127.0.0.1:8080",
                                        "-d",
                                        "Block node to be referenced")
                                .exposingOutputTo(newRegisteredNodeCapturer(rnId::set)),
                        // Create a DAB node that references the registered node
                        sourcingContextual(spec1 -> yahcliNodes(
                                        "create",
                                        "-a",
                                        "23",
                                        "-d",
                                        "DAB node referencing registered node",
                                        "-k",
                                        asYcDefaultNetworkKey(dabAdminKeyFile),
                                        "--gossipCaCertificate",
                                        certFilePath.toString(),
                                        "-h",
                                        certFilePath.toString(),
                                        "-g",
                                        "127.0.0.1:50211",
                                        "-s",
                                        "a.b.com:50212",
                                        "--associatedRegisteredNode",
                                        Long.toString(rnId.get()))
                                .exposingOutputTo(newNodeCapturer(dabNodeId::set))),
                        // Attempt to delete while still referenced — must fail
                        sourcingContextual(spec2 -> yahcliRegisteredNodes("delete", "-n", Long.toString(rnId.get()))
                                .expectFail()
                                .exposingOutputTo(output ->
                                        assertTrue(output.contains("FAILED to delete registeredNode" + rnId.get())))),
                        // Delete the DAB node first to clear the association
                        sourcingContextual(spec3 -> yahcliNodes("delete", "-n", Long.toString(dabNodeId.get()))
                                .exposingOutputTo(output ->
                                        assertTrue(output.contains("node" + dabNodeId.get() + " has been deleted")))),
                        // Now the registered node can be deleted
                        sourcingContextual(spec4 -> yahcliRegisteredNodes("delete", "-n", Long.toString(rnId.get()))
                                .exposingOutputTo(output -> assertTrue(
                                        output.contains("registeredNode" + rnId.get() + " has been deleted")))))));
    }

    /**
     * Attempts to delete a registered node ID that does not exist on the network.
     * The network rejects the transaction, and the command must report failure.
     */
    @HapiTest
    final Stream<DynamicTest> deleteNonExistentRegisteredNodeFails() {
        final var adminKeyFile = "rn_del_nonexistent.pem";
        final long nonExistentNodeId = 999_999L;
        return hapiTest(
                newKeyNamed("adminKey")
                        .shape(SigControl.ED25519_ON)
                        .exportingTo(() -> asYcDefaultNetworkKey(adminKeyFile), "keypass"),
                doingContextual(spec -> allRunFor(
                        spec,
                        yahcliRegisteredNodes(
                                        "delete",
                                        "-n",
                                        Long.toString(nonExistentNodeId),
                                        "-k",
                                        asYcDefaultNetworkKey(adminKeyFile))
                                .expectFail()
                                .exposingOutputTo(output -> assertTrue(
                                        output.contains("FAILED to delete registeredNode" + nonExistentNodeId))))));
    }
}
