// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1137;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getScheduleInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.registeredNodeCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.registeredNodeDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.registeredNodeUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleCreate;
import static com.hedera.services.bdd.spec.transactions.node.RegisteredEndpointUtils.blockNodeEndpoint;
import static com.hedera.services.bdd.spec.transactions.node.RegisteredEndpointUtils.blockNodeEndpointIp;
import static com.hedera.services.bdd.spec.transactions.node.RegisteredEndpointUtils.mirrorNodeEndpoint;
import static com.hedera.services.bdd.spec.transactions.node.RegisteredEndpointUtils.rpcRelayEndpoint;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.hip869.NodeCreateTest.generateX509Certificates;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_NODE_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_REGISTERED_ENDPOINT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_REGISTERED_ENDPOINT_ADDRESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_REGISTERED_ENDPOINT_TYPE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MAX_REGISTERED_NODES_EXCEEDED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.REGISTERED_ENDPOINTS_EXCEEDED_LIMIT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.REGISTERED_NODE_STILL_REFERENCED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hederahashgraph.api.proto.java.RegisteredServiceEndpoint;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;

@HapiTestLifecycle
public class RegisteredNodeTest {
    private static final String ADMIN_KEY = "adminKey";
    private static final String NEW_ADMIN_KEY = "newAdminKey";
    private static final String CREATE_TXN = "registeredNodeCreate";

    private static List<X509Certificate> gossipCertificates;

    @BeforeAll
    static void beforeAll() {
        gossipCertificates = generateX509Certificates(1);
    }

    private static final List<RegisteredServiceEndpoint> DEFAULT_ENDPOINTS = List.of(blockNodeEndpoint(
            "blocknode.example.com", 8080, RegisteredServiceEndpoint.BlockNodeEndpoint.BlockNodeApi.STATUS));

    @HapiTest
    @DisplayName("create, update (admin key rotation), and delete")
    final Stream<DynamicTest> crudHappyPath() {
        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                newKeyNamed(NEW_ADMIN_KEY),
                registeredNodeCreate("rn")
                        .adminKey(ADMIN_KEY)
                        .serviceEndpoints(DEFAULT_ENDPOINTS)
                        .via(CREATE_TXN)
                        .hasKnownStatus(SUCCESS),
                getTxnRecord(CREATE_TXN).logged().hasPriority(recordWith().hasNonZeroRegisteredNodeId()),
                registeredNodeUpdate("rn")
                        .description("new-desc")
                        .adminKey(NEW_ADMIN_KEY)
                        .signedBy(DEFAULT_PAYER, ADMIN_KEY, NEW_ADMIN_KEY)
                        .hasKnownStatus(SUCCESS),
                registeredNodeDelete("rn")
                        .signedBy(DEFAULT_PAYER, NEW_ADMIN_KEY)
                        .hasKnownStatus(SUCCESS));
    }

    @HapiTest
    @DisplayName("description-only update preserves endpoints and admin key")
    final Stream<DynamicTest> descriptionOnlyUpdate() {
        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                registeredNodeCreate("rn")
                        .adminKey(ADMIN_KEY)
                        .serviceEndpoints(DEFAULT_ENDPOINTS)
                        .hasKnownStatus(SUCCESS),
                registeredNodeUpdate("rn")
                        .description("updated-desc")
                        .signedBy(DEFAULT_PAYER, ADMIN_KEY)
                        .hasKnownStatus(SUCCESS));
    }

    @HapiTest
    @DisplayName("endpoints replace replaces entire list")
    final Stream<DynamicTest> endpointsReplaceUpdate() {
        final var replacementEndpoints = List.of(blockNodeEndpointIp(
                new byte[] {10, 0, 0, 1}, 9090, RegisteredServiceEndpoint.BlockNodeEndpoint.BlockNodeApi.PUBLISH));
        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                registeredNodeCreate("rn")
                        .adminKey(ADMIN_KEY)
                        .serviceEndpoints(DEFAULT_ENDPOINTS)
                        .hasKnownStatus(SUCCESS),
                registeredNodeUpdate("rn")
                        .serviceEndpoints(replacementEndpoints)
                        .signedBy(DEFAULT_PAYER, ADMIN_KEY)
                        .hasKnownStatus(SUCCESS));
    }

    @HapiTest
    @DisplayName("update requires admin key signature, key rotation requires dual signature")
    final Stream<DynamicTest> updateSignatureRequirements() {
        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                newKeyNamed(NEW_ADMIN_KEY),
                registeredNodeCreate("rn")
                        .adminKey(ADMIN_KEY)
                        .serviceEndpoints(DEFAULT_ENDPOINTS)
                        .hasKnownStatus(SUCCESS),
                // Update without admin key sig → INVALID_SIGNATURE
                registeredNodeUpdate("rn")
                        .description("unauthorized")
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(INVALID_SIGNATURE),
                // Rotation signed only by old key → INVALID_SIGNATURE
                registeredNodeUpdate("rn")
                        .adminKey(NEW_ADMIN_KEY)
                        .signedBy(DEFAULT_PAYER, ADMIN_KEY)
                        .hasKnownStatus(INVALID_SIGNATURE),
                // Rotation signed by both old and new keys → SUCCESS
                registeredNodeUpdate("rn")
                        .adminKey(NEW_ADMIN_KEY)
                        .signedBy(DEFAULT_PAYER, ADMIN_KEY, NEW_ADMIN_KEY)
                        .hasKnownStatus(SUCCESS));
    }

    @HapiTest
    @DisplayName("update of non-existent registered node fails with INVALID_NODE_ID")
    final Stream<DynamicTest> updateNonExistentNodeFails() {
        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                registeredNodeUpdate(() -> 999_999L)
                        .description("ghost")
                        .signedBy(DEFAULT_PAYER)
                        .hasPrecheck(INVALID_NODE_ID));
    }

    @HapiTest
    @DisplayName("consensus node create and update with associated registered node")
    final Stream<DynamicTest> consensusNodeWithAssociatedRegisteredNode() throws CertificateEncodingException {
        final var nodeAccount = "nodeAccount";
        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                cryptoCreate(nodeAccount),
                registeredNodeCreate("rn")
                        .adminKey(ADMIN_KEY)
                        .serviceEndpoints(DEFAULT_ENDPOINTS)
                        .hasKnownStatus(SUCCESS),
                withOpContext((spec, opLog) -> {
                    final var create = nodeCreate("testNode", nodeAccount)
                            .adminKey(ADMIN_KEY)
                            .gossipCaCertificate(gossipCertificates.getFirst().getEncoded())
                            .associatedRegisteredNode(List.of(spec.registry().getRegisteredNodeId("rn")))
                            .hasKnownStatus(SUCCESS);
                    allRunFor(spec, create);
                }),
                nodeUpdate("testNode")
                        .associatedRegisteredNode(List.of())
                        .signedBy(DEFAULT_PAYER, ADMIN_KEY)
                        .hasKnownStatus(SUCCESS));
    }

    @HapiTest
    @DisplayName("consensus node create fails with non-existent associated registered node")
    final Stream<DynamicTest> createWithNonExistentAssociatedRegisteredNodeFails() throws CertificateEncodingException {
        final var nodeAccount = "nodeAccount";
        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                cryptoCreate(nodeAccount),
                nodeCreate("testNode", nodeAccount)
                        .adminKey(ADMIN_KEY)
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded())
                        .associatedRegisteredNode(List.of(999_999L))
                        .hasKnownStatus(INVALID_NODE_ID));
    }

    @HapiTest
    @DisplayName("delete registered node fails when still referenced by consensus node")
    final Stream<DynamicTest> deleteReferencedRegisteredNodeFails() throws CertificateEncodingException {
        final var nodeAccount = "nodeAccount";
        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                cryptoCreate(nodeAccount),
                registeredNodeCreate("rn")
                        .adminKey(ADMIN_KEY)
                        .serviceEndpoints(DEFAULT_ENDPOINTS)
                        .hasKnownStatus(SUCCESS),
                withOpContext((spec, opLog) -> {
                    final var create = nodeCreate("testNode", nodeAccount)
                            .adminKey(ADMIN_KEY)
                            .gossipCaCertificate(gossipCertificates.getFirst().getEncoded())
                            .associatedRegisteredNode(List.of(spec.registry().getRegisteredNodeId("rn")))
                            .hasKnownStatus(SUCCESS);
                    allRunFor(spec, create);
                }),
                registeredNodeDelete("rn")
                        .signedBy(DEFAULT_PAYER, ADMIN_KEY)
                        .hasKnownStatus(REGISTERED_NODE_STILL_REFERENCED));
    }

    @HapiTest
    @DisplayName("delete registered node succeeds after consensus node clears reference")
    final Stream<DynamicTest> deleteRegisteredNodeAfterClearingReference() throws CertificateEncodingException {
        final var nodeAccount = "nodeAccount";
        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                cryptoCreate(nodeAccount),
                registeredNodeCreate("rn")
                        .adminKey(ADMIN_KEY)
                        .serviceEndpoints(DEFAULT_ENDPOINTS)
                        .hasKnownStatus(SUCCESS),
                withOpContext((spec, opLog) -> {
                    final var create = nodeCreate("testNode", nodeAccount)
                            .adminKey(ADMIN_KEY)
                            .gossipCaCertificate(gossipCertificates.getFirst().getEncoded())
                            .associatedRegisteredNode(List.of(spec.registry().getRegisteredNodeId("rn")))
                            .hasKnownStatus(SUCCESS);
                    allRunFor(spec, create);
                }),
                // Clear the reference
                nodeUpdate("testNode")
                        .associatedRegisteredNode(List.of())
                        .signedBy(DEFAULT_PAYER, ADMIN_KEY)
                        .hasKnownStatus(SUCCESS),
                // Now delete should succeed
                registeredNodeDelete("rn").signedBy(DEFAULT_PAYER, ADMIN_KEY).hasKnownStatus(SUCCESS));
    }

    // ─── Create negatives ──────────────────────────────────────────

    @HapiTest
    @DisplayName("create fails without admin key signature")
    final Stream<DynamicTest> createWithMissingAdminKeySigFails() {
        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                registeredNodeCreate("rn")
                        .adminKey(ADMIN_KEY)
                        .serviceEndpoints(DEFAULT_ENDPOINTS)
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(INVALID_SIGNATURE));
    }

    @HapiTest
    @DisplayName("create fails with endpoint missing type")
    final Stream<DynamicTest> createWithInvalidEndpointTypeFails() {
        final var endpointMissingType = List.of(RegisteredServiceEndpoint.newBuilder()
                .setDomainName("blocknode.example.com")
                .setPort(8080)
                // no block_node/mirror_node/rpc_relay set
                .build());
        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                registeredNodeCreate("rn")
                        .adminKey(ADMIN_KEY)
                        .serviceEndpoints(endpointMissingType)
                        .hasKnownStatus(INVALID_REGISTERED_ENDPOINT_TYPE));
    }

    // ─── Update negative ───────────────────────────────────────────

    @HapiTest
    @DisplayName("update fails with endpoint missing type")
    final Stream<DynamicTest> updateWithInvalidEndpointFails() {
        final var endpointMissingType = List.of(RegisteredServiceEndpoint.newBuilder()
                .setDomainName("blocknode.example.com")
                .setPort(8080)
                .build());
        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                registeredNodeCreate("rn")
                        .adminKey(ADMIN_KEY)
                        .serviceEndpoints(DEFAULT_ENDPOINTS)
                        .hasKnownStatus(SUCCESS),
                registeredNodeUpdate("rn")
                        .serviceEndpoints(endpointMissingType)
                        .signedBy(DEFAULT_PAYER, ADMIN_KEY)
                        .hasKnownStatus(INVALID_REGISTERED_ENDPOINT_TYPE));
    }

    // ─── Delete negatives ──────────────────────────────────────────

    @HapiTest
    @DisplayName("delete non-existent registered node fails with INVALID_NODE_ID")
    final Stream<DynamicTest> deleteNonExistentRegisteredNodeFails() {
        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                registeredNodeDelete(() -> 999_999L).signedBy(DEFAULT_PAYER).hasPrecheck(INVALID_NODE_ID));
    }

    @HapiTest
    @DisplayName("delete fails without admin key signature")
    final Stream<DynamicTest> deleteWithMissingAdminKeySigFails() {
        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                registeredNodeCreate("rn")
                        .adminKey(ADMIN_KEY)
                        .serviceEndpoints(DEFAULT_ENDPOINTS)
                        .hasKnownStatus(SUCCESS),
                registeredNodeDelete("rn").signedBy(DEFAULT_PAYER).hasKnownStatus(INVALID_SIGNATURE));
    }

    // ─── Endpoint validation negatives ────────────────────────────

    @HapiTest
    @DisplayName("create fails with empty endpoints list")
    final Stream<DynamicTest> createWithEmptyEndpointsFails() {
        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                registeredNodeCreate("rn")
                        .adminKey(ADMIN_KEY)
                        .serviceEndpoints(List.of())
                        .hasKnownStatus(INVALID_REGISTERED_ENDPOINT));
    }

    @HapiTest
    @DisplayName("create fails with invalid IP address length")
    final Stream<DynamicTest> createWithInvalidIpAddressFails() {
        // 3-byte IP is neither IPv4 (4) nor IPv6 (16)
        final var badIpEndpoint = List.of(blockNodeEndpointIp(
                new byte[] {10, 0, 1}, 8080, RegisteredServiceEndpoint.BlockNodeEndpoint.BlockNodeApi.STATUS));
        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                registeredNodeCreate("rn")
                        .adminKey(ADMIN_KEY)
                        .serviceEndpoints(badIpEndpoint)
                        .hasKnownStatus(INVALID_REGISTERED_ENDPOINT_ADDRESS));
    }

    @HapiTest
    @DisplayName("create fails with invalid FQDN")
    final Stream<DynamicTest> createWithInvalidFqdnFails() {
        // Domain starting with hyphen violates DNS label rules
        final var badFqdnEndpoint = List.of(blockNodeEndpoint(
                "-invalid.example.com", 8080, RegisteredServiceEndpoint.BlockNodeEndpoint.BlockNodeApi.STATUS));
        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                registeredNodeCreate("rn")
                        .adminKey(ADMIN_KEY)
                        .serviceEndpoints(badFqdnEndpoint)
                        .hasKnownStatus(INVALID_REGISTERED_ENDPOINT_ADDRESS));
    }

    @LeakyHapiTest(overrides = {"nodes.maxRegisteredServiceEndpoint"})
    @DisplayName("create fails when exceeding max registered endpoints")
    final Stream<DynamicTest> createWithTooManyEndpointsFails() {
        // Lower the limit to 2 and try to create with 3
        final var threeEndpoints = List.of(
                blockNodeEndpoint(
                        "a.example.com", 8080, RegisteredServiceEndpoint.BlockNodeEndpoint.BlockNodeApi.STATUS),
                blockNodeEndpoint(
                        "b.example.com", 8081, RegisteredServiceEndpoint.BlockNodeEndpoint.BlockNodeApi.PUBLISH),
                blockNodeEndpoint(
                        "c.example.com",
                        8082,
                        RegisteredServiceEndpoint.BlockNodeEndpoint.BlockNodeApi.SUBSCRIBE_STREAM));
        return hapiTest(
                overriding("nodes.maxRegisteredServiceEndpoint", "2"),
                newKeyNamed(ADMIN_KEY),
                registeredNodeCreate("rn")
                        .adminKey(ADMIN_KEY)
                        .serviceEndpoints(threeEndpoints)
                        .hasKnownStatus(REGISTERED_ENDPOINTS_EXCEEDED_LIMIT));
    }

    // ─── Endpoint type happy paths ────────────────────────────────

    @HapiTest
    @DisplayName("create registered node with mirror-node endpoint")
    final Stream<DynamicTest> createWithMirrorNodeEndpoint() {
        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                registeredNodeCreate("rn")
                        .adminKey(ADMIN_KEY)
                        .serviceEndpoints(List.of(mirrorNodeEndpoint("mirror.example.com", 443)))
                        .hasKnownStatus(SUCCESS));
    }

    @HapiTest
    @DisplayName("create registered node with rpc-relay endpoint")
    final Stream<DynamicTest> createWithRpcRelayEndpoint() {
        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                registeredNodeCreate("rn")
                        .adminKey(ADMIN_KEY)
                        .serviceEndpoints(List.of(rpcRelayEndpoint("relay.example.com", 443)))
                        .hasKnownStatus(SUCCESS));
    }

    @HapiTest
    @DisplayName("create registered node with mixed endpoint types")
    final Stream<DynamicTest> createWithMixedEndpointTypes() {
        final var mixedEndpoints = List.of(
                blockNodeEndpoint(
                        "block.example.com", 8080, RegisteredServiceEndpoint.BlockNodeEndpoint.BlockNodeApi.STATUS),
                mirrorNodeEndpoint("mirror.example.com", 443),
                rpcRelayEndpoint("relay.example.com", 443));
        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                registeredNodeCreate("rn")
                        .adminKey(ADMIN_KEY)
                        .serviceEndpoints(mixedEndpoints)
                        .hasKnownStatus(SUCCESS));
    }

    // ─── Privileged delete ────────────────────────────────────────

    @HapiTest
    @DisplayName("privileged payer can delete without admin key signature")
    final Stream<DynamicTest> privilegedDeleteWithoutAdminKeySig() {
        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                registeredNodeCreate("rn")
                        .adminKey(ADMIN_KEY)
                        .serviceEndpoints(DEFAULT_ENDPOINTS)
                        .hasKnownStatus(SUCCESS),
                // GENESIS (treasury) is a privileged payer — no admin key sig required
                registeredNodeDelete("rn").payingWith(GENESIS).signedBy(GENESIS).hasKnownStatus(SUCCESS));
    }

    // ─── Associated registered node limit ─────────────────────────

    @LeakyHapiTest(overrides = {"nodes.maxAssociatedRegisteredNodes"})
    @DisplayName("consensus node create fails when exceeding associated registered node limit")
    final Stream<DynamicTest> consensusNodeExceedsAssociatedRegisteredNodeLimit() throws CertificateEncodingException {
        final var nodeAccount = "nodeAccount";
        return hapiTest(
                overriding("nodes.maxAssociatedRegisteredNodes", "1"),
                newKeyNamed(ADMIN_KEY),
                cryptoCreate(nodeAccount),
                registeredNodeCreate("rn1")
                        .adminKey(ADMIN_KEY)
                        .serviceEndpoints(DEFAULT_ENDPOINTS)
                        .hasKnownStatus(SUCCESS),
                registeredNodeCreate("rn2")
                        .adminKey(ADMIN_KEY)
                        .serviceEndpoints(DEFAULT_ENDPOINTS)
                        .hasKnownStatus(SUCCESS),
                // Try to associate 2 registered nodes when limit is 1
                withOpContext((spec, opLog) -> {
                    final var create = nodeCreate("testNode", nodeAccount)
                            .adminKey(ADMIN_KEY)
                            .gossipCaCertificate(gossipCertificates.getFirst().getEncoded())
                            .associatedRegisteredNode(List.of(
                                    spec.registry().getRegisteredNodeId("rn1"),
                                    spec.registry().getRegisteredNodeId("rn2")))
                            .hasKnownStatus(MAX_REGISTERED_NODES_EXCEEDED);
                    allRunFor(spec, create);
                }));
    }

    // ─── AtomicBatch ───────────────────────────────────────────────

    @HapiTest
    @DisplayName("create, update, and delete registered node via atomic batches")
    final Stream<DynamicTest> registeredNodeViaAtomicBatch() {
        final var batchOperator = "batchOperator";
        final var createdId = new AtomicLong();
        return hapiTest(
                cryptoCreate(batchOperator).balance(ONE_MILLION_HBARS),
                newKeyNamed(ADMIN_KEY),
                // Batch 1: create
                atomicBatch(registeredNodeCreate("rn")
                                .adminKey(ADMIN_KEY)
                                .serviceEndpoints(DEFAULT_ENDPOINTS)
                                .exposingCreatedIdTo(createdId::set)
                                .batchKey(batchOperator))
                        .payingWith(batchOperator)
                        .hasKnownStatus(SUCCESS),
                // Batch 2: update + delete
                withOpContext((spec, opLog) -> {
                    final var batch = atomicBatch(
                                    registeredNodeUpdate(createdId::get)
                                            .description("batch-updated")
                                            .signedBy(DEFAULT_PAYER, ADMIN_KEY)
                                            .batchKey(batchOperator),
                                    registeredNodeDelete(createdId::get)
                                            .signedBy(DEFAULT_PAYER, ADMIN_KEY)
                                            .batchKey(batchOperator))
                            .payingWith(batchOperator)
                            .hasKnownStatus(SUCCESS);
                    allRunFor(spec, batch);
                }));
    }

    // ─── ScheduleTransactions ──────────────────────────────────────

    @HapiTest
    @DisplayName("create, update, and delete registered node via scheduled transactions")
    final Stream<DynamicTest> registeredNodeViaScheduleTransactions() {
        final var createdId = new AtomicLong();
        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                // Schedule a create and verify execution
                scheduleCreate(
                                "schedCreate",
                                registeredNodeCreate("rn")
                                        .adminKey(ADMIN_KEY)
                                        .serviceEndpoints(DEFAULT_ENDPOINTS)
                                        .exposingCreatedIdTo(createdId::set))
                        .alsoSigningWith(ADMIN_KEY)
                        .hasKnownStatus(SUCCESS),
                getScheduleInfo("schedCreate").isExecuted(),
                // Schedule an update and verify execution
                withOpContext((spec, opLog) -> {
                    final var sched = scheduleCreate(
                                    "schedUpdate",
                                    registeredNodeUpdate(createdId::get).description("scheduled-desc"))
                            .alsoSigningWith(ADMIN_KEY)
                            .hasKnownStatus(SUCCESS);
                    allRunFor(spec, sched);
                }),
                getScheduleInfo("schedUpdate").isExecuted(),
                // Schedule a delete and verify execution
                withOpContext((spec, opLog) -> {
                    final var sched = scheduleCreate("schedDelete", registeredNodeDelete(createdId::get))
                            .alsoSigningWith(ADMIN_KEY)
                            .hasKnownStatus(SUCCESS);
                    allRunFor(spec, sched);
                }),
                getScheduleInfo("schedDelete").isExecuted());
    }

    // ─── GovernanceTransactions ────────────────────────────────────

    @HapiTest
    @DisplayName("create, update, and delete registered node via governance account")
    final Stream<DynamicTest> registeredNodeViaGovernanceAccount() {
        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                // Governance create
                registeredNodeCreate("rn")
                        .adminKey(ADMIN_KEY)
                        .serviceEndpoints(DEFAULT_ENDPOINTS)
                        .payingWith(GENESIS)
                        .hasKnownStatus(SUCCESS),
                // Governance update
                registeredNodeUpdate("rn")
                        .description("gov-update")
                        .signedBy(DEFAULT_PAYER, ADMIN_KEY)
                        .payingWith(GENESIS)
                        .hasKnownStatus(SUCCESS),
                // Governance delete
                registeredNodeDelete("rn")
                        .signedBy(DEFAULT_PAYER, ADMIN_KEY)
                        .payingWith(GENESIS)
                        .hasKnownStatus(SUCCESS));
    }
}
