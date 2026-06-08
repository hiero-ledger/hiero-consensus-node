// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test.regression;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcingContextual;
import static com.hedera.services.yahcli.test.YahcliTestBase.REGRESSION;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.asYcDefaultNetworkKey;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.newRegisteredNodeCapturer;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.yahcliRegisteredNodes;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.keys.SigControl;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Regression tests for the {@code yahcli registeredNodes update} command.
 *
 * <p>Each test creates a registered node and then updates it with a specific
 * combination of options, asserting that the command succeeds. Tests require a
 * running subprocess network; run via {@code ./gradlew :yahcli:testSubprocess}.
 *
 * @see <a href="https://hips.hedera.com/hip/hip-1137">HIP-1137</a>
 */
@Tag(REGRESSION)
public class UpdateRegisteredNodesCommandsTest {

    // -------------------------------------------------------------------------
    // Description update tests
    // -------------------------------------------------------------------------

    /**
     * Creates a registered node then updates only its description. Verifies that
     * the update succeeds without requiring any endpoint changes.
     */
    @HapiTest
    final Stream<DynamicTest> updateDescriptionOnly() {
        final var createdId = new AtomicLong();
        final var adminKeyFile = "rn_upd_desc_only.pem";
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
                                        "127.0.0.1:8080:STATUS",
                                        "-d",
                                        "Initial description")
                                .exposingOutputTo(newRegisteredNodeCapturer(createdId::set)))),
                sourcingContextual(spec -> yahcliRegisteredNodes(
                                "update",
                                "-n",
                                Long.toString(createdId.get()),
                                "-k",
                                asYcDefaultNetworkKey(adminKeyFile),
                                "-d",
                                "Updated description")
                        .exposingOutputTo(output -> assertTrue(
                                output.contains("registeredNode" + createdId.get() + " has been updated")))));
    }

    // -------------------------------------------------------------------------
    // Endpoint replacement tests
    // -------------------------------------------------------------------------

    /**
     * Creates a registered node with a block node endpoint, then replaces it
     * with a mirror node endpoint. Verifies that the update's endpoint list
     * fully replaces the original.
     */
    @HapiTest
    final Stream<DynamicTest> updateEndpointsReplaceBlockWithMirror() {
        final var createdId = new AtomicLong();
        final var adminKeyFile = "rn_upd_ep_replace.pem";
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
                                        "127.0.0.1:8080:STATUS")
                                .exposingOutputTo(newRegisteredNodeCapturer(createdId::set)))),
                sourcingContextual(spec -> yahcliRegisteredNodes(
                                "update",
                                "-n",
                                Long.toString(createdId.get()),
                                "-k",
                                asYcDefaultNetworkKey(adminKeyFile),
                                "--mirrorNodeEndpoint",
                                "127.0.0.1:5551")
                        .exposingOutputTo(output -> assertTrue(
                                output.contains("registeredNode" + createdId.get() + " has been updated")))));
    }

    /**
     * Creates a registered node with a mirror node endpoint, then replaces its
     * endpoints with multiple block node endpoints exposing different APIs. Verifies
     * that the update's endpoint list replaces all previous endpoints.
     */
    @HapiTest
    final Stream<DynamicTest> updateEndpointsWithMultipleBlockNodeApis() {
        final var createdId = new AtomicLong();
        final var adminKeyFile = "rn_upd_multi_api.pem";
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
                                        "--mirrorNodeEndpoint",
                                        "127.0.0.1:5551")
                                .exposingOutputTo(newRegisteredNodeCapturer(createdId::set)))),
                sourcingContextual(spec -> yahcliRegisteredNodes(
                                "update",
                                "-n",
                                Long.toString(createdId.get()),
                                "-k",
                                asYcDefaultNetworkKey(adminKeyFile),
                                "--blockNodeEndpoint",
                                "blocknode.example.com:8080:STATUS",
                                "--blockNodeEndpoint",
                                "blocknode.example.com:8081:PUBLISH",
                                "--blockNodeEndpoint",
                                "blocknode.example.com:8082:SUBSCRIBE_STREAM")
                        .exposingOutputTo(output -> assertTrue(
                                output.contains("registeredNode" + createdId.get() + " has been updated")))));
    }

    /**
     * Creates a registered node with a single-API block node endpoint, then updates it
     * to a single endpoint advertising multiple APIs via the comma-separated format.
     * Exercises the repeated {@code endpoint_api} field on update.
     */
    @HapiTest
    final Stream<DynamicTest> updateEndpointWithMultiApiCommaSeparated() {
        final var createdId = new AtomicLong();
        final var adminKeyFile = "rn_upd_multi_api_csv.pem";
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
                                        "127.0.0.1:8080:STATUS")
                                .exposingOutputTo(newRegisteredNodeCapturer(createdId::set)))),
                sourcingContextual(spec -> yahcliRegisteredNodes(
                                "update",
                                "-n",
                                Long.toString(createdId.get()),
                                "-k",
                                asYcDefaultNetworkKey(adminKeyFile),
                                "--blockNodeEndpoint",
                                "127.0.0.1:8080:STATUS,PUBLISH,SUBSCRIBE_STREAM")
                        .exposingOutputTo(output -> assertTrue(
                                output.contains("registeredNode" + createdId.get() + " has been updated")))));
    }

    /**
     * Creates a registered node with a block node endpoint, then adds TLS-enabled
     * endpoints for all three service types during update. Exercises the mixed
     * endpoint type path with TLS flags.
     */
    @HapiTest
    final Stream<DynamicTest> updateEndpointsToAllTypesWithTls() {
        final var createdId = new AtomicLong();
        final var adminKeyFile = "rn_upd_all_tls.pem";
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
                                        "127.0.0.1:8080:STATUS")
                                .exposingOutputTo(newRegisteredNodeCapturer(createdId::set)))),
                sourcingContextual(spec -> yahcliRegisteredNodes(
                                "update",
                                "-n",
                                Long.toString(createdId.get()),
                                "-k",
                                asYcDefaultNetworkKey(adminKeyFile),
                                "--blockNodeEndpoint",
                                "infra.example.com:8443:PUBLISH:tls",
                                "--mirrorNodeEndpoint",
                                "infra.example.com:5443:tls",
                                "--rpcRelayEndpoint",
                                "infra.example.com:7443:tls")
                        .exposingOutputTo(output -> assertTrue(
                                output.contains("registeredNode" + createdId.get() + " has been updated")))));
    }

    // -------------------------------------------------------------------------
    // Admin key rotation tests
    // -------------------------------------------------------------------------

    /**
     * Creates a registered node with an ED25519 admin key, then rotates it to a new
     * ED25519 key. Both the old and new admin keys must sign the update transaction.
     */
    @HapiTest
    final Stream<DynamicTest> rotateAdminKeyEd25519ToEd25519() {
        final var createdId = new AtomicLong();
        final var adminKeyFile = "rn_rotate_old.pem";
        final var newAdminKeyFile = "rn_rotate_new.pem";
        return hapiTest(
                newKeyNamed("adminKey")
                        .shape(SigControl.ED25519_ON)
                        .exportingTo(() -> asYcDefaultNetworkKey(adminKeyFile), "keypass"),
                newKeyNamed("newAdminKey")
                        .shape(SigControl.ED25519_ON)
                        .exportingTo(() -> asYcDefaultNetworkKey(newAdminKeyFile), "keypass"),
                doingContextual(spec -> allRunFor(
                        spec,
                        yahcliRegisteredNodes(
                                        "create",
                                        "-k",
                                        asYcDefaultNetworkKey(adminKeyFile),
                                        "--blockNodeEndpoint",
                                        "127.0.0.1:8080:STATUS")
                                .exposingOutputTo(newRegisteredNodeCapturer(createdId::set)))),
                sourcingContextual(spec -> yahcliRegisteredNodes(
                                "update",
                                "-n",
                                Long.toString(createdId.get()),
                                "-k",
                                asYcDefaultNetworkKey(adminKeyFile),
                                "-nk",
                                asYcDefaultNetworkKey(newAdminKeyFile))
                        .exposingOutputTo(output -> assertTrue(
                                output.contains("registeredNode" + createdId.get() + " has been updated")))));
    }

    /**
     * Creates a registered node with an ED25519 admin key, then rotates it to a SECP256K1
     * key. Verifies that yahcli correctly loads and signs with both key types during rotation.
     */
    @HapiTest
    final Stream<DynamicTest> rotateAdminKeyEd25519ToSecp256k1() {
        final var createdId = new AtomicLong();
        final var adminKeyFile = "rn_rotate_ed_to_secp_old.pem";
        final var newAdminKeyFile = "rn_rotate_ed_to_secp_new.pem";
        return hapiTest(
                newKeyNamed("adminKey")
                        .shape(SigControl.ED25519_ON)
                        .exportingTo(() -> asYcDefaultNetworkKey(adminKeyFile), "keypass"),
                newKeyNamed("newAdminKey")
                        .shape(SigControl.SECP256K1_ON)
                        .exportingTo(() -> asYcDefaultNetworkKey(newAdminKeyFile), "keypass"),
                doingContextual(spec -> allRunFor(
                        spec,
                        yahcliRegisteredNodes(
                                        "create",
                                        "-k",
                                        asYcDefaultNetworkKey(adminKeyFile),
                                        "--blockNodeEndpoint",
                                        "127.0.0.1:8080:STATUS")
                                .exposingOutputTo(newRegisteredNodeCapturer(createdId::set)))),
                sourcingContextual(spec -> yahcliRegisteredNodes(
                                "update",
                                "-n",
                                Long.toString(createdId.get()),
                                "-k",
                                asYcDefaultNetworkKey(adminKeyFile),
                                "-nk",
                                asYcDefaultNetworkKey(newAdminKeyFile))
                        .exposingOutputTo(output -> assertTrue(
                                output.contains("registeredNode" + createdId.get() + " has been updated")))));
    }

    // -------------------------------------------------------------------------
    // Combined update tests
    // -------------------------------------------------------------------------

    /**
     * Creates a registered node, then updates description and endpoints in a single
     * command. Verifies that multiple fields can be updated simultaneously.
     */
    @HapiTest
    final Stream<DynamicTest> updateDescriptionAndEndpointsTogether() {
        final var createdId = new AtomicLong();
        final var adminKeyFile = "rn_upd_desc_ep.pem";
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
                                        "127.0.0.1:8080:STATUS",
                                        "-d",
                                        "Original description")
                                .exposingOutputTo(newRegisteredNodeCapturer(createdId::set)))),
                sourcingContextual(spec -> yahcliRegisteredNodes(
                                "update",
                                "-n",
                                Long.toString(createdId.get()),
                                "-k",
                                asYcDefaultNetworkKey(adminKeyFile),
                                "-d",
                                "Updated description",
                                "--rpcRelayEndpoint",
                                "relay.example.com:7546")
                        .exposingOutputTo(output -> assertTrue(
                                output.contains("registeredNode" + createdId.get() + " has been updated")))));
    }

    // -------------------------------------------------------------------------
    // Negative tests
    // -------------------------------------------------------------------------

    /**
     * Attempts to update a registered node's block node endpoint with duplicate APIs
     * (PUBLISH,PUBLISH). The network rejects this with INVALID_REGISTERED_ENDPOINT.
     */
    @HapiTest
    final Stream<DynamicTest> updateWithDuplicateBlockNodeApisFails() {
        final var createdId = new AtomicLong();
        final var adminKeyFile = "rn_upd_dup_apis.pem";
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
                                        "127.0.0.1:8080:STATUS")
                                .exposingOutputTo(newRegisteredNodeCapturer(createdId::set)))),
                sourcingContextual(spec -> yahcliRegisteredNodes(
                                "update",
                                "-n",
                                Long.toString(createdId.get()),
                                "-k",
                                asYcDefaultNetworkKey(adminKeyFile),
                                "--blockNodeEndpoint",
                                "127.0.0.1:8080:PUBLISH,PUBLISH")
                        .expectFail()
                        .exposingOutputTo(output -> assertTrue(
                                output.contains("FAILED"), "Expected failure for duplicate APIs in update"))));
    }

    /**
     * Attempts to update a registered node without supplying the admin key. The
     * payer signature alone is insufficient, so the update must fail.
     */
    @HapiTest
    final Stream<DynamicTest> updateWithoutAdminKeyFails() {
        final var createdId = new AtomicLong();
        final var adminKeyFile = "rn_upd_no_key.pem";
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
                                        "127.0.0.1:8080:STATUS")
                                .exposingOutputTo(newRegisteredNodeCapturer(createdId::set)))),
                // Update without admin key - payer signature alone is not enough
                sourcingContextual(spec -> yahcliRegisteredNodes(
                                "update", "-n", Long.toString(createdId.get()), "-d", "Should fail without admin key")
                        .expectFail()
                        .exposingOutputTo(output ->
                                assertTrue(output.contains("FAILED to update registeredNode" + createdId.get())))));
    }

    /**
     * Attempts to update a registered node with a wrong (unrelated) admin key. The
     * update must fail because the signed transaction does not satisfy the node's key.
     */
    @HapiTest
    final Stream<DynamicTest> updateWithWrongAdminKeyFails() {
        final var createdId = new AtomicLong();
        final var adminKeyFile = "rn_upd_right_key.pem";
        final var wrongKeyFile = "rn_upd_wrong_key.pem";
        return hapiTest(
                newKeyNamed("adminKey")
                        .shape(SigControl.ED25519_ON)
                        .exportingTo(() -> asYcDefaultNetworkKey(adminKeyFile), "keypass"),
                newKeyNamed("wrongKey")
                        .shape(SigControl.ED25519_ON)
                        .exportingTo(() -> asYcDefaultNetworkKey(wrongKeyFile), "keypass"),
                doingContextual(spec -> allRunFor(
                        spec,
                        yahcliRegisteredNodes(
                                        "create",
                                        "-k",
                                        asYcDefaultNetworkKey(adminKeyFile),
                                        "--blockNodeEndpoint",
                                        "127.0.0.1:8080:STATUS")
                                .exposingOutputTo(newRegisteredNodeCapturer(createdId::set)))),
                // Update with wrong admin key - must fail
                sourcingContextual(spec -> yahcliRegisteredNodes(
                                "update",
                                "-n",
                                Long.toString(createdId.get()),
                                "-k",
                                asYcDefaultNetworkKey(wrongKeyFile),
                                "-d",
                                "Should fail with wrong admin key")
                        .expectFail()
                        .exposingOutputTo(output ->
                                assertTrue(output.contains("FAILED to update registeredNode" + createdId.get())))));
    }

    /**
     * Attempts to update a registered node ID that does not exist on the network.
     * The network rejects the transaction with INVALID_NODE_ID, and the command
     * must report failure.
     */
    @HapiTest
    final Stream<DynamicTest> updateNonExistentRegisteredNodeFails() {
        final var adminKeyFile = "rn_upd_nonexistent.pem";
        final long nonExistentNodeId = 999_999L;
        return hapiTest(
                newKeyNamed("adminKey")
                        .shape(SigControl.ED25519_ON)
                        .exportingTo(() -> asYcDefaultNetworkKey(adminKeyFile), "keypass"),
                doingContextual(spec -> allRunFor(
                        spec,
                        yahcliRegisteredNodes(
                                        "update",
                                        "-n",
                                        Long.toString(nonExistentNodeId),
                                        "-k",
                                        asYcDefaultNetworkKey(adminKeyFile),
                                        "-d",
                                        "Should fail for non-existent node")
                                .expectFail()
                                .exposingOutputTo(output -> assertTrue(
                                        output.contains("FAILED to update registeredNode" + nonExistentNodeId))))));
    }

    // -------------------------------------------------------------------------
    // Post-rotation lifecycle test
    // -------------------------------------------------------------------------

    /**
     * Closes the loop on admin key rotation: after rotating from {@code adminKey} to
     * {@code newAdminKey}, verifies that:
     * <ol>
     *   <li>A subsequent update signed with the <em>new</em> key succeeds — proving the
     *       rotation was accepted on-chain.</li>
     *   <li>A subsequent update signed with the <em>old</em> key fails — proving the old
     *       key no longer controls the node.</li>
     * </ol>
     */
    @HapiTest
    final Stream<DynamicTest> postKeyRotationUpdateSucceedsWithNewKeyAndFailsWithOldKey() {
        final var createdId = new AtomicLong();
        final var adminKeyFile = "rn_post_rotate_old.pem";
        final var newAdminKeyFile = "rn_post_rotate_new.pem";
        return hapiTest(
                newKeyNamed("adminKey")
                        .shape(SigControl.ED25519_ON)
                        .exportingTo(() -> asYcDefaultNetworkKey(adminKeyFile), "keypass"),
                newKeyNamed("newAdminKey")
                        .shape(SigControl.ED25519_ON)
                        .exportingTo(() -> asYcDefaultNetworkKey(newAdminKeyFile), "keypass"),
                // Create the node with the original admin key
                doingContextual(spec -> allRunFor(
                        spec,
                        yahcliRegisteredNodes(
                                        "create",
                                        "-k",
                                        asYcDefaultNetworkKey(adminKeyFile),
                                        "--blockNodeEndpoint",
                                        "127.0.0.1:8080:STATUS")
                                .exposingOutputTo(newRegisteredNodeCapturer(createdId::set)))),
                // Rotate: both old and new keys sign
                sourcingContextual(spec -> yahcliRegisteredNodes(
                                "update",
                                "-n",
                                Long.toString(createdId.get()),
                                "-k",
                                asYcDefaultNetworkKey(adminKeyFile),
                                "-nk",
                                asYcDefaultNetworkKey(newAdminKeyFile))
                        .exposingOutputTo(output ->
                                assertTrue(output.contains("registeredNode" + createdId.get() + " has been updated")))),
                // New key now controls the node — update must succeed
                sourcingContextual(spec -> yahcliRegisteredNodes(
                                "update",
                                "-n",
                                Long.toString(createdId.get()),
                                "-k",
                                asYcDefaultNetworkKey(newAdminKeyFile),
                                "-d",
                                "Updated with new key after rotation")
                        .exposingOutputTo(output ->
                                assertTrue(output.contains("registeredNode" + createdId.get() + " has been updated")))),
                // Old key no longer controls the node — update must fail
                sourcingContextual(spec -> yahcliRegisteredNodes(
                                "update",
                                "-n",
                                Long.toString(createdId.get()),
                                "-k",
                                asYcDefaultNetworkKey(adminKeyFile),
                                "-d",
                                "Should fail: old key no longer valid")
                        .expectFail()
                        .exposingOutputTo(output ->
                                assertTrue(output.contains("FAILED to update registeredNode" + createdId.get())))));
    }

    // -------------------------------------------------------------------------
    // SECP256K1 signing test
    // -------------------------------------------------------------------------

    /**
     * Creates a registered node with a SECP256K1 admin key, then uses that same key
     * to sign a plain update (description change). Verifies that yahcli correctly loads
     * an ECDSA PEM file and signs an update — not just a create — with it.
     */
    @HapiTest
    final Stream<DynamicTest> updateWithSecp256k1AdminKey() {
        final var createdId = new AtomicLong();
        final var adminKeyFile = "rn_upd_secp256k1.pem";
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
                                        "127.0.0.1:8080:STATUS",
                                        "-d",
                                        "Node with SECP256K1 admin key")
                                .exposingOutputTo(newRegisteredNodeCapturer(createdId::set)))),
                sourcingContextual(spec -> yahcliRegisteredNodes(
                                "update",
                                "-n",
                                Long.toString(createdId.get()),
                                "-k",
                                asYcDefaultNetworkKey(adminKeyFile),
                                "-d",
                                "Updated with SECP256K1 admin key")
                        .exposingOutputTo(output -> assertTrue(
                                output.contains("registeredNode" + createdId.get() + " has been updated")))));
    }

    // -------------------------------------------------------------------------
    // Dotted node ID format test
    // -------------------------------------------------------------------------

    /**
     * Uses the shard.realm.num dotted format for {@code -n} (e.g. {@code 0.0.5}) instead
     * of a plain number. Verifies that {@code normalizePossibleIdLiteral} correctly strips
     * the shard and realm prefix in the update command code path, which is independent of
     * the create command and could regress separately.
     */
    @HapiTest
    final Stream<DynamicTest> updateNodeIdInDottedFormat() {
        final var createdId = new AtomicLong();
        final var adminKeyFile = "rn_upd_dotted_id.pem";
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
                                        "127.0.0.1:8080:STATUS")
                                .exposingOutputTo(newRegisteredNodeCapturer(createdId::set)))),
                // Use dotted 0.0.<id> format — normalizePossibleIdLiteral must strip the prefix
                sourcingContextual(spec -> yahcliRegisteredNodes(
                                "update",
                                "-n",
                                "0.0." + createdId.get(),
                                "-k",
                                asYcDefaultNetworkKey(adminKeyFile),
                                "-d",
                                "Updated via dotted node ID format")
                        .exposingOutputTo(output -> assertTrue(
                                output.contains("registeredNode" + createdId.get() + " has been updated")))));
    }

    // -------------------------------------------------------------------------
    // SECP256K1 → ED25519 key rotation test
    // -------------------------------------------------------------------------

    /**
     * Creates a registered node with a SECP256K1 admin key, then rotates it to an
     * ED25519 key. Completes the rotation type matrix: the existing tests cover
     * ED25519→ED25519 and ED25519→SECP256K1, but not SECP256K1→ED25519. This direction
     * exercises a distinct code path in yahcli's key loading since the <em>current</em>
     * key read from disk is ECDSA rather than the default EdDSA.
     */
    @HapiTest
    final Stream<DynamicTest> rotateAdminKeySecp256k1ToEd25519() {
        final var createdId = new AtomicLong();
        final var adminKeyFile = "rn_rotate_secp_to_ed_old.pem";
        final var newAdminKeyFile = "rn_rotate_secp_to_ed_new.pem";
        return hapiTest(
                newKeyNamed("adminKey")
                        .shape(SigControl.SECP256K1_ON)
                        .exportingTo(() -> asYcDefaultNetworkKey(adminKeyFile), "keypass"),
                newKeyNamed("newAdminKey")
                        .shape(SigControl.ED25519_ON)
                        .exportingTo(() -> asYcDefaultNetworkKey(newAdminKeyFile), "keypass"),
                doingContextual(spec -> allRunFor(
                        spec,
                        yahcliRegisteredNodes(
                                        "create",
                                        "-k",
                                        asYcDefaultNetworkKey(adminKeyFile),
                                        "--blockNodeEndpoint",
                                        "127.0.0.1:8080:STATUS")
                                .exposingOutputTo(newRegisteredNodeCapturer(createdId::set)))),
                sourcingContextual(spec -> yahcliRegisteredNodes(
                                "update",
                                "-n",
                                Long.toString(createdId.get()),
                                "-k",
                                asYcDefaultNetworkKey(adminKeyFile),
                                "-nk",
                                asYcDefaultNetworkKey(newAdminKeyFile))
                        .exposingOutputTo(output -> assertTrue(
                                output.contains("registeredNode" + createdId.get() + " has been updated")))));
    }
}
