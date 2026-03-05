// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test.regression;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.yahcli.test.YahcliTestBase.REGRESSION;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.asYcDefaultNetworkKey;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.newRegisteredNodeCapturer;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.yahcliRegisteredNodes;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.keys.SigControl;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Regression tests for the {@code yahcli registeredNodes create} command.
 *
 * <p>Each test creates a registered node with a different endpoint configuration and
 * asserts that the command succeeds and returns a valid registered node ID.
 * Tests require a running subprocess network; run via {@code ./gradlew :yahcli:testSubprocess}.
 *
 * @see <a href="https://hips.hedera.com/hip/hip-1137">HIP-1137</a>
 */
@Tag(REGRESSION)
public class RegisteredNodesCommandsTest {

    // -------------------------------------------------------------------------
    // Individual endpoint type tests
    // -------------------------------------------------------------------------

    /**
     * Creates a registered node with a single block node endpoint using the default
     * STATUS API and an IPv4 address. This is the minimal valid block node registration.
     */
    @HapiTest
    final Stream<DynamicTest> createWithBlockNodeStatusEndpoint() {
        final var createdId = new AtomicLong();
        final var adminKeyFile = "rn_block_status.pem";
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
                                        "127.0.0.1:8080",
                                        "-d",
                                        "Block node with default STATUS API")
                                .exposingOutputTo(newRegisteredNodeCapturer(createdId::set)))));
    }

    /**
     * Creates a registered node with a block node endpoint using the explicit PUBLISH API.
     * Uses an FQDN address to exercise domain name parsing.
     */
    @HapiTest
    final Stream<DynamicTest> createWithBlockNodePublishEndpoint() {
        final var createdId = new AtomicLong();
        final var adminKeyFile = "rn_block_publish.pem";
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
                                        "blocknode.example.com:8080:PUBLISH",
                                        "-d",
                                        "Block node with PUBLISH API")
                                .exposingOutputTo(newRegisteredNodeCapturer(createdId::set)))));
    }

    /**
     * Creates a registered node with a single mirror node endpoint using an IPv4 address.
     */
    @HapiTest
    final Stream<DynamicTest> createWithMirrorNodeEndpoint() {
        final var createdId = new AtomicLong();
        final var adminKeyFile = "rn_mirror.pem";
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
                                        "127.0.0.1:5551",
                                        "-d",
                                        "Mirror node")
                                .exposingOutputTo(newRegisteredNodeCapturer(createdId::set)))));
    }

    /**
     * Creates a registered node with a single RPC relay endpoint using an FQDN address.
     */
    @HapiTest
    final Stream<DynamicTest> createWithRpcRelayEndpoint() {
        final var createdId = new AtomicLong();
        final var adminKeyFile = "rn_rpc_relay.pem";
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
                                        "--rpcRelayEndpoint",
                                        "relay.example.com:7546",
                                        "-d",
                                        "RPC relay")
                                .exposingOutputTo(newRegisteredNodeCapturer(createdId::set)))));
    }

    /**
     * Creates a registered node with a TLS-enabled block node endpoint.
     * Uses FQDN + SUBSCRIBE_STREAM API, which is a realistic production-like configuration
     * for a block node that clients stream blocks from.
     */
    @HapiTest
    final Stream<DynamicTest> createWithTlsBlockNodeEndpoint() {
        final var createdId = new AtomicLong();
        final var adminKeyFile = "rn_block_tls.pem";
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
                                        "blocknode.example.com:8443:SUBSCRIBE_STREAM:tls",
                                        "-d",
                                        "Block node with TLS and SUBSCRIBE_STREAM API")
                                .exposingOutputTo(newRegisteredNodeCapturer(createdId::set)))));
    }

    // -------------------------------------------------------------------------
    // Key type tests
    // -------------------------------------------------------------------------

    /**
     * Creates a registered node using a SECP256K1 (ECDSA secp256k1) admin key. Verifies that
     * yahcli correctly loads an ECDSA PEM file and signs the create transaction with it.
     */
    @HapiTest
    final Stream<DynamicTest> createWithSecp256k1AdminKey() {
        final var createdId = new AtomicLong();
        final var adminKeyFile = "rn_secp256k1.pem";
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
                                        "Block node with SECP256K1 admin key")
                                .exposingOutputTo(newRegisteredNodeCapturer(createdId::set)))));
    }

    // -------------------------------------------------------------------------
    // Combination tests
    // -------------------------------------------------------------------------

    /**
     * Creates a registered node exposing multiple block node APIs on separate ports.
     * This mirrors a real block node that separates its STATUS, PUBLISH, and
     * SUBSCRIBE_STREAM endpoints for operational reasons (e.g. different ACLs per port).
     */
    @HapiTest
    final Stream<DynamicTest> createWithMultipleBlockNodeApis() {
        final var createdId = new AtomicLong();
        final var adminKeyFile = "rn_multi_block_api.pem";
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
                                        "--blockNodeEndpoint",
                                        "127.0.0.1:8081:PUBLISH",
                                        "--blockNodeEndpoint",
                                        "127.0.0.1:8082:SUBSCRIBE_STREAM",
                                        "-d",
                                        "Block node exposing STATUS, PUBLISH, and SUBSCRIBE_STREAM")
                                .exposingOutputTo(newRegisteredNodeCapturer(createdId::set)))));
    }

    /**
     * Creates a registered node that acts as both a block node and a mirror node,
     * co-located on the same host. Both endpoints use TLS.
     */
    @HapiTest
    final Stream<DynamicTest> createWithBlockAndMirrorNodeEndpoints() {
        final var createdId = new AtomicLong();
        final var adminKeyFile = "rn_block_mirror.pem";
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
                                        "node.example.com:8443:PUBLISH:tls",
                                        "--mirrorNodeEndpoint",
                                        "node.example.com:5443:tls",
                                        "-d",
                                        "Co-located block node and mirror node")
                                .exposingOutputTo(newRegisteredNodeCapturer(createdId::set)))));
    }

    /**
     * Creates a registered node that exposes all three service types: block node, mirror node,
     * and RPC relay — all with TLS. Represents a full-service Hiero infrastructure node.
     */
    @HapiTest
    final Stream<DynamicTest> createWithAllEndpointTypes() {
        final var createdId = new AtomicLong();
        final var adminKeyFile = "rn_all_types.pem";
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
                                        "infra.example.com:8443:PUBLISH:tls",
                                        "--mirrorNodeEndpoint",
                                        "infra.example.com:5443:tls",
                                        "--rpcRelayEndpoint",
                                        "infra.example.com:7443:tls",
                                        "-d",
                                        "Full-service registered node: block, mirror, and RPC relay")
                                .exposingOutputTo(newRegisteredNodeCapturer(createdId::set)))));
    }
}
