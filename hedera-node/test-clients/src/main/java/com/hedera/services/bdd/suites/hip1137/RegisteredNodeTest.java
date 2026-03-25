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
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.hip869.NodeCreateTest.generateX509Certificates;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_REGISTERED_ENDPOINT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_REGISTERED_ENDPOINT_ADDRESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_REGISTERED_ENDPOINT_TYPE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_REGISTERED_NODE_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MAX_REGISTERED_NODES_EXCEEDED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.NOT_SUPPORTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.REGISTERED_ENDPOINTS_EXCEEDED_LIMIT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.REGISTERED_NODE_STILL_ASSOCIATED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.spec.keys.SigControl;
import com.hederahashgraph.api.proto.java.RegisteredServiceEndpoint;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;

@HapiTestLifecycle
public class RegisteredNodeTest {
    private static final String ADMIN_KEY = "adminKey";
    private static final String NEW_ADMIN_KEY = "newAdminKey";
    private static final String CREATE_TXN = "registeredNodeCreate";
    private static final String UPDATE_TXN = "registeredNodeUpdate";
    private static final String NODE_ACCOUNT = "nodeAccount";
    private static final String REGISTERED_NODE = "registeredNode";
    private static final String TEST_NODE = "testNode";
    private static final String PAYER = "payer";
    private static final long NON_EXISTENT_ID = 999_999L;

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
                registeredNodeCreate(REGISTERED_NODE)
                        .adminKey(ADMIN_KEY)
                        .serviceEndpoints(DEFAULT_ENDPOINTS)
                        .via(CREATE_TXN)
                        .hasKnownStatus(SUCCESS),
                getTxnRecord(CREATE_TXN).logged().hasPriority(recordWith().hasNonZeroRegisteredNodeId()),
                registeredNodeUpdate(REGISTERED_NODE)
                        .description("new-desc")
                        .adminKey(NEW_ADMIN_KEY)
                        .signedBy(DEFAULT_PAYER, ADMIN_KEY, NEW_ADMIN_KEY)
                        .via(UPDATE_TXN)
                        .hasKnownStatus(SUCCESS),
                getTxnRecord(UPDATE_TXN).logged(),
                registeredNodeDelete(REGISTERED_NODE)
                        .signedBy(DEFAULT_PAYER, NEW_ADMIN_KEY)
                        .hasKnownStatus(SUCCESS));
    }

    @HapiTest
    @DisplayName("create → update (admin key rotation) works with SECP256K1 admin key")
    final Stream<DynamicTest> crudHappyPathWithSecp256k1AdminKey() {
        final var createdId = new AtomicLong();
        return hapiTest(
                newKeyNamed(ADMIN_KEY).shape(SigControl.SECP256K1_ON),
                newKeyNamed(NEW_ADMIN_KEY).shape(SigControl.SECP256K1_ON),
                registeredNodeCreate("rn")
                        .adminKey(ADMIN_KEY)
                        .serviceEndpoints(DEFAULT_ENDPOINTS)
                        .via(CREATE_TXN)
                        .exposingCreatedIdTo(createdId::set)
                        .hasKnownStatus(SUCCESS),
                getTxnRecord(CREATE_TXN).logged().hasPriority(recordWith().hasNonZeroRegisteredNodeId()),
                withOpContext((spec, opLog) -> {
                    final var update = registeredNodeUpdate(createdId::get)
                            .description("new-desc")
                            .adminKey(NEW_ADMIN_KEY)
                            .signedBy(DEFAULT_PAYER, ADMIN_KEY, NEW_ADMIN_KEY)
                            .via("secp256k1UpdateTxn")
                            .hasKnownStatus(SUCCESS);
                    final var record = getTxnRecord("secp256k1UpdateTxn").logged();
                    allRunFor(spec, update, record);
                }));
    }

    @HapiTest
    @DisplayName("description-only update preserves endpoints and admin key")
    final Stream<DynamicTest> descriptionOnlyUpdate() {
        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                registeredNodeCreate(REGISTERED_NODE)
                        .adminKey(ADMIN_KEY)
                        .serviceEndpoints(DEFAULT_ENDPOINTS)
                        .hasKnownStatus(SUCCESS),
                registeredNodeUpdate(REGISTERED_NODE)
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
                registeredNodeCreate(REGISTERED_NODE)
                        .adminKey(ADMIN_KEY)
                        .serviceEndpoints(DEFAULT_ENDPOINTS)
                        .hasKnownStatus(SUCCESS),
                registeredNodeUpdate(REGISTERED_NODE)
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
                registeredNodeCreate(REGISTERED_NODE)
                        .adminKey(ADMIN_KEY)
                        .serviceEndpoints(DEFAULT_ENDPOINTS)
                        .hasKnownStatus(SUCCESS),
                // Update without admin key sig → INVALID_SIGNATURE
                registeredNodeUpdate(REGISTERED_NODE)
                        .description("unauthorized")
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(INVALID_SIGNATURE),
                // Rotation signed only by old key → INVALID_SIGNATURE
                registeredNodeUpdate(REGISTERED_NODE)
                        .adminKey(NEW_ADMIN_KEY)
                        .signedBy(DEFAULT_PAYER, ADMIN_KEY)
                        .hasKnownStatus(INVALID_SIGNATURE),
                // Rotation signed by both old and new keys → SUCCESS
                registeredNodeUpdate(REGISTERED_NODE)
                        .adminKey(NEW_ADMIN_KEY)
                        .signedBy(DEFAULT_PAYER, ADMIN_KEY, NEW_ADMIN_KEY)
                        .hasKnownStatus(SUCCESS));
    }

    @HapiTest
    @DisplayName("update of non-existent registered node fails with INVALID_REGISTERED_NODE_ID")
    final Stream<DynamicTest> updateNonExistentNodeFails() {
        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                registeredNodeUpdate(() -> NON_EXISTENT_ID)
                        .description("ghost")
                        .signedBy(DEFAULT_PAYER)
                        .hasPrecheck(INVALID_REGISTERED_NODE_ID));
    }

    @HapiTest
    @DisplayName("consensus node create and update with associated registered node")
    final Stream<DynamicTest> consensusNodeWithAssociatedRegisteredNode() throws CertificateEncodingException {
        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                cryptoCreate(NODE_ACCOUNT),
                registeredNodeCreate(REGISTERED_NODE)
                        .adminKey(ADMIN_KEY)
                        .serviceEndpoints(DEFAULT_ENDPOINTS)
                        .hasKnownStatus(SUCCESS),
                withOpContext((spec, opLog) -> {
                    final var create = nodeCreate(TEST_NODE, NODE_ACCOUNT)
                            .adminKey(ADMIN_KEY)
                            .gossipCaCertificate(gossipCertificates.getFirst().getEncoded())
                            .associatedRegisteredNode(List.of(spec.registry().getRegisteredNodeId(REGISTERED_NODE)))
                            .hasKnownStatus(SUCCESS);
                    allRunFor(spec, create);
                }),
                nodeUpdate(TEST_NODE)
                        .associatedRegisteredNode(List.of())
                        .signedBy(DEFAULT_PAYER, ADMIN_KEY)
                        .hasKnownStatus(SUCCESS));
    }

    @HapiTest
    @DisplayName("consensus node create fails with non-existent associated registered node")
    final Stream<DynamicTest> createWithNonExistentAssociatedRegisteredNodeFails() throws CertificateEncodingException {
        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                cryptoCreate(NODE_ACCOUNT),
                nodeCreate(TEST_NODE, NODE_ACCOUNT)
                        .adminKey(ADMIN_KEY)
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded())
                        .associatedRegisteredNode(List.of(NON_EXISTENT_ID))
                        .hasKnownStatus(INVALID_REGISTERED_NODE_ID));
    }

    @HapiTest
    @DisplayName("delete registered node fails when still referenced by consensus node")
    final Stream<DynamicTest> deleteReferencedRegisteredNodeFails() throws CertificateEncodingException {
        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                cryptoCreate(NODE_ACCOUNT),
                registeredNodeCreate(REGISTERED_NODE)
                        .adminKey(ADMIN_KEY)
                        .serviceEndpoints(DEFAULT_ENDPOINTS)
                        .hasKnownStatus(SUCCESS),
                withOpContext((spec, opLog) -> {
                    final var create = nodeCreate(TEST_NODE, NODE_ACCOUNT)
                            .adminKey(ADMIN_KEY)
                            .gossipCaCertificate(gossipCertificates.getFirst().getEncoded())
                            .associatedRegisteredNode(List.of(spec.registry().getRegisteredNodeId(REGISTERED_NODE)))
                            .hasKnownStatus(SUCCESS);
                    allRunFor(spec, create);
                }),
                registeredNodeDelete(REGISTERED_NODE)
                        .signedBy(DEFAULT_PAYER, ADMIN_KEY)
                        .hasKnownStatus(REGISTERED_NODE_STILL_ASSOCIATED));
    }

    @HapiTest
    @DisplayName("delete registered node succeeds after consensus node clears reference")
    final Stream<DynamicTest> deleteRegisteredNodeAfterClearingReference() throws CertificateEncodingException {
        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                cryptoCreate(NODE_ACCOUNT),
                registeredNodeCreate(REGISTERED_NODE)
                        .adminKey(ADMIN_KEY)
                        .serviceEndpoints(DEFAULT_ENDPOINTS)
                        .hasKnownStatus(SUCCESS),
                withOpContext((spec, opLog) -> {
                    final var create = nodeCreate(TEST_NODE, NODE_ACCOUNT)
                            .adminKey(ADMIN_KEY)
                            .gossipCaCertificate(gossipCertificates.getFirst().getEncoded())
                            .associatedRegisteredNode(List.of(spec.registry().getRegisteredNodeId(REGISTERED_NODE)))
                            .hasKnownStatus(SUCCESS);
                    allRunFor(spec, create);
                }),
                // Clear the reference
                nodeUpdate(TEST_NODE)
                        .associatedRegisteredNode(List.of())
                        .signedBy(DEFAULT_PAYER, ADMIN_KEY)
                        .hasKnownStatus(SUCCESS),
                // Now delete should succeed
                registeredNodeDelete(REGISTERED_NODE)
                        .signedBy(DEFAULT_PAYER, ADMIN_KEY)
                        .hasKnownStatus(SUCCESS));
    }

    // ─── Create negatives ──────────────────────────────────────────

    @HapiTest
    @DisplayName("create fails without admin key signature")
    final Stream<DynamicTest> createWithMissingAdminKeySigFails() {
        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                registeredNodeCreate(REGISTERED_NODE)
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
                registeredNodeCreate(REGISTERED_NODE)
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
                registeredNodeCreate(REGISTERED_NODE)
                        .adminKey(ADMIN_KEY)
                        .serviceEndpoints(DEFAULT_ENDPOINTS)
                        .hasKnownStatus(SUCCESS),
                registeredNodeUpdate(REGISTERED_NODE)
                        .serviceEndpoints(endpointMissingType)
                        .signedBy(DEFAULT_PAYER, ADMIN_KEY)
                        .hasKnownStatus(INVALID_REGISTERED_ENDPOINT_TYPE));
    }

    // ─── Delete negatives ──────────────────────────────────────────

    @HapiTest
    @DisplayName("delete non-existent registered node fails with INVALID_REGISTERED_NODE_ID")
    final Stream<DynamicTest> deleteNonExistentRegisteredNodeFails() {
        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                registeredNodeDelete(() -> NON_EXISTENT_ID)
                        .signedBy(DEFAULT_PAYER)
                        .hasPrecheck(INVALID_REGISTERED_NODE_ID));
    }

    @HapiTest
    @DisplayName("delete fails without admin key signature")
    final Stream<DynamicTest> deleteWithMissingAdminKeySigFails() {
        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                cryptoCreate(PAYER),
                registeredNodeCreate(REGISTERED_NODE)
                        .adminKey(ADMIN_KEY)
                        .serviceEndpoints(DEFAULT_ENDPOINTS)
                        .hasKnownStatus(SUCCESS),
                registeredNodeDelete(REGISTERED_NODE)
                        .payingWith(PAYER)
                        .signedBy(PAYER)
                        .hasKnownStatus(INVALID_SIGNATURE));
    }

    // ─── Endpoint validation negatives ────────────────────────────

    @HapiTest
    @DisplayName("create fails with empty endpoints list")
    final Stream<DynamicTest> createWithEmptyEndpointsFails() {
        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                registeredNodeCreate(REGISTERED_NODE)
                        .adminKey(ADMIN_KEY)
                        .serviceEndpoints(List.of())
                        .hasPrecheck(INVALID_REGISTERED_ENDPOINT));
    }

    @HapiTest
    @DisplayName("create fails with invalid IP address length")
    final Stream<DynamicTest> createWithInvalidIpAddressFails() {
        // 3-byte IP is neither IPv4 (4) nor IPv6 (16)
        final var badIpEndpoint = List.of(blockNodeEndpointIp(
                new byte[] {10, 0, 1}, 8080, RegisteredServiceEndpoint.BlockNodeEndpoint.BlockNodeApi.STATUS));
        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                registeredNodeCreate(REGISTERED_NODE)
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
                registeredNodeCreate(REGISTERED_NODE)
                        .adminKey(ADMIN_KEY)
                        .serviceEndpoints(badFqdnEndpoint)
                        .hasKnownStatus(INVALID_REGISTERED_ENDPOINT_ADDRESS));
    }

    @HapiTest
    @DisplayName("create fails when exceeding max registered endpoints")
    final Stream<DynamicTest> createWithTooManyEndpointsFails() {
        final var tooManyEndpoints = java.util.stream.IntStream.rangeClosed(1, 51)
                .mapToObj(i -> blockNodeEndpoint(
                        "node" + i + ".example.com",
                        8080 + i,
                        RegisteredServiceEndpoint.BlockNodeEndpoint.BlockNodeApi.STATUS))
                .toList();
        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                registeredNodeCreate(REGISTERED_NODE)
                        .adminKey(ADMIN_KEY)
                        .serviceEndpoints(tooManyEndpoints)
                        .hasKnownStatus(REGISTERED_ENDPOINTS_EXCEEDED_LIMIT));
    }

    @HapiTest
    @DisplayName("create fails when general-service description exceeds max bytes")
    final Stream<DynamicTest> createWithGeneralServiceDescriptionTooLong() {
        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                registeredNodeCreate(REGISTERED_NODE)
                        .adminKey(ADMIN_KEY)
                        .serviceEndpoints(List.of(generalServiceEndpoint("svc.example.com", 8443, "x".repeat(101))))
                        .hasKnownStatus(INVALID_REGISTERED_ENDPOINT));
    }

    @HapiTest
    @DisplayName("create fails when general-service description contains null byte")
    final Stream<DynamicTest> createWithGeneralServiceDescriptionNullByte() {
        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                registeredNodeCreate(REGISTERED_NODE)
                        .adminKey(ADMIN_KEY)
                        .serviceEndpoints(List.of(generalServiceEndpoint("svc.example.com", 8443, "valid\0hidden")))
                        .hasKnownStatus(INVALID_REGISTERED_ENDPOINT));
    }

    // ─── Endpoint type happy paths ────────────────────────────────

    @HapiTest
    @DisplayName("create registered node with mirror-node endpoint")
    final Stream<DynamicTest> createWithMirrorNodeEndpoint() {
        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                registeredNodeCreate(REGISTERED_NODE)
                        .adminKey(ADMIN_KEY)
                        .serviceEndpoints(List.of(mirrorNodeEndpoint("mirror.example.com", 443)))
                        .hasKnownStatus(SUCCESS));
    }

    @HapiTest
    @DisplayName("create registered node with rpc-relay endpoint")
    final Stream<DynamicTest> createWithRpcRelayEndpoint() {
        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                registeredNodeCreate(REGISTERED_NODE)
                        .adminKey(ADMIN_KEY)
                        .serviceEndpoints(List.of(rpcRelayEndpoint("relay.example.com", 443)))
                        .hasKnownStatus(SUCCESS));
    }

    @HapiTest
    @DisplayName("create registered node with general-service endpoint")
    final Stream<DynamicTest> createWithGeneralServiceEndpoint() {
        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                registeredNodeCreate(REGISTERED_NODE)
                        .adminKey(ADMIN_KEY)
                        .serviceEndpoints(
                                List.of(generalServiceEndpoint("service.example.com", 8443, "Custom indexer")))
                        .hasKnownStatus(SUCCESS));
    }

    @HapiTest
    @DisplayName("create registered node with mixed endpoint types")
    final Stream<DynamicTest> createWithMixedEndpointTypes() {
        final var mixedEndpoints = List.of(
                blockNodeEndpoint(
                        "block.example.com", 8080, RegisteredServiceEndpoint.BlockNodeEndpoint.BlockNodeApi.STATUS),
                mirrorNodeEndpoint("mirror.example.com", 443),
                rpcRelayEndpoint("relay.example.com", 443),
                generalServiceEndpoint("custom.example.com", 9090, "Custom service"));
        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                registeredNodeCreate(REGISTERED_NODE)
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
                registeredNodeCreate(REGISTERED_NODE)
                        .adminKey(ADMIN_KEY)
                        .serviceEndpoints(DEFAULT_ENDPOINTS)
                        .hasKnownStatus(SUCCESS),
                // GENESIS (treasury) is a privileged payer — no admin key sig required
                registeredNodeDelete(REGISTERED_NODE)
                        .payingWith(GENESIS)
                        .signedBy(GENESIS)
                        .hasKnownStatus(SUCCESS));
    }

    // ─── Associated registered node limit ─────────────────────────

    @HapiTest
    @DisplayName("consensus node create fails when exceeding associated registered node limit")
    final Stream<DynamicTest> consensusNodeExceedsAssociatedRegisteredNodeLimit() throws CertificateEncodingException {
        // Default maxAssociatedRegisteredNodes is 20; create 21 registered nodes and try to associate all
        final var ops = new java.util.ArrayList<com.hedera.services.bdd.spec.SpecOperation>();
        ops.add(newKeyNamed(ADMIN_KEY));
        ops.add(cryptoCreate(NODE_ACCOUNT));
        for (int i = 1; i <= 21; i++) {
            ops.add(registeredNodeCreate("rn" + i)
                    .adminKey(ADMIN_KEY)
                    .serviceEndpoints(DEFAULT_ENDPOINTS)
                    .hasKnownStatus(SUCCESS));
        }
        ops.add(withOpContext((spec, opLog) -> {
            final var ids = java.util.stream.IntStream.rangeClosed(1, 21)
                    .mapToObj(i -> spec.registry().getRegisteredNodeId("rn" + i))
                    .toList();
            final var create = nodeCreate(TEST_NODE, NODE_ACCOUNT)
                    .adminKey(ADMIN_KEY)
                    .gossipCaCertificate(gossipCertificates.getFirst().getEncoded())
                    .associatedRegisteredNode(ids)
                    .hasKnownStatus(MAX_REGISTERED_NODES_EXCEEDED);
            allRunFor(spec, create);
        }));
        return hapiTest(ops.toArray(com.hedera.services.bdd.spec.SpecOperation[]::new));
    }

    @LeakyHapiTest
    @DisplayName("consensus node then registered node allocate sequential IDs from shared space")
    final Stream<DynamicTest> consensusThenRegisteredNodeSharesIdSpace() throws CertificateEncodingException {
        final var consensusNodeId = new AtomicLong();
        final var registeredNodeId = new AtomicLong();
        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                cryptoCreate(NODE_ACCOUNT),
                nodeCreate(TEST_NODE, NODE_ACCOUNT)
                        .adminKey(ADMIN_KEY)
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded())
                        .exposingCreatedIdTo(consensusNodeId::set),
                registeredNodeCreate(REGISTERED_NODE)
                        .adminKey(ADMIN_KEY)
                        .serviceEndpoints(DEFAULT_ENDPOINTS)
                        .exposingCreatedIdTo(registeredNodeId::set),
                withOpContext((spec, opLog) -> assertEquals(
                        consensusNodeId.get() + 1,
                        registeredNodeId.get(),
                        "Registered node ID should be consensus node ID + 1")));
    }

    @LeakyHapiTest
    @DisplayName("registered node then consensus node allocate sequential IDs from shared space")
    final Stream<DynamicTest> registeredThenConsensusNodeSharesIdSpace() throws CertificateEncodingException {
        final var registeredNodeId = new AtomicLong();
        final var consensusNodeId = new AtomicLong();
        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                cryptoCreate(NODE_ACCOUNT),
                registeredNodeCreate(REGISTERED_NODE)
                        .adminKey(ADMIN_KEY)
                        .serviceEndpoints(DEFAULT_ENDPOINTS)
                        .exposingCreatedIdTo(registeredNodeId::set),
                nodeCreate(TEST_NODE, NODE_ACCOUNT)
                        .adminKey(ADMIN_KEY)
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded())
                        .exposingCreatedIdTo(consensusNodeId::set),
                withOpContext((spec, opLog) -> assertEquals(
                        registeredNodeId.get() + 1,
                        consensusNodeId.get(),
                        "Consensus node ID should be registered node ID + 1")));
    }

    // ─── Feature flag disabled ──────────────────────────────────────

    @LeakyHapiTest(overrides = {"nodes.registeredNodesEnabled"})
    @DisplayName("create fails with NOT_SUPPORTED when registeredNodesEnabled is false")
    final Stream<DynamicTest> createFailsWhenFeatureDisabled() {
        return hapiTest(
                overriding("nodes.registeredNodesEnabled", "false"),
                newKeyNamed(ADMIN_KEY),
                registeredNodeCreate(REGISTERED_NODE)
                        .adminKey(ADMIN_KEY)
                        .serviceEndpoints(DEFAULT_ENDPOINTS)
                        .hasPrecheck(NOT_SUPPORTED));
    }

    @LeakyHapiTest(overrides = {"nodes.registeredNodesEnabled"})
    @DisplayName("update fails with NOT_SUPPORTED when registeredNodesEnabled is false")
    final Stream<DynamicTest> updateFailsWhenFeatureDisabled() {
        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                registeredNodeCreate(REGISTERED_NODE)
                        .adminKey(ADMIN_KEY)
                        .serviceEndpoints(DEFAULT_ENDPOINTS)
                        .hasKnownStatus(SUCCESS),
                overriding("nodes.registeredNodesEnabled", "false"),
                registeredNodeUpdate(REGISTERED_NODE)
                        .description("updated")
                        .signedBy(DEFAULT_PAYER, ADMIN_KEY)
                        .hasPrecheck(NOT_SUPPORTED));
    }

    @LeakyHapiTest(overrides = {"nodes.registeredNodesEnabled"})
    @DisplayName("delete fails with NOT_SUPPORTED when registeredNodesEnabled is false")
    final Stream<DynamicTest> deleteFailsWhenFeatureDisabled() {
        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                registeredNodeCreate(REGISTERED_NODE)
                        .adminKey(ADMIN_KEY)
                        .serviceEndpoints(DEFAULT_ENDPOINTS)
                        .hasKnownStatus(SUCCESS),
                overriding("nodes.registeredNodesEnabled", "false"),
                registeredNodeDelete(REGISTERED_NODE)
                        .signedBy(DEFAULT_PAYER, ADMIN_KEY)
                        .hasPrecheck(NOT_SUPPORTED));
    }

    @LeakyHapiTest(overrides = {"nodes.registeredNodesEnabled", "fees.simpleFeesEnabled"})
    @DisplayName("create fails with NOT_SUPPORTED when registeredNodesEnabled is false and simple fees enabled")
    final Stream<DynamicTest> createFailsWhenFeatureDisabledWithSimpleFees() {
        return hapiTest(
                overriding("nodes.registeredNodesEnabled", "false"),
                overriding("fees.simpleFeesEnabled", "true"),
                newKeyNamed(ADMIN_KEY),
                registeredNodeCreate(REGISTERED_NODE)
                        .adminKey(ADMIN_KEY)
                        .serviceEndpoints(DEFAULT_ENDPOINTS)
                        .hasPrecheck(NOT_SUPPORTED));
    }

    @LeakyHapiTest(overrides = {"nodes.registeredNodesEnabled", "fees.simpleFeesEnabled"})
    @DisplayName("update fails with NOT_SUPPORTED when registeredNodesEnabled is false and simple fees enabled")
    final Stream<DynamicTest> updateFailsWhenFeatureDisabledWithSimpleFees() {
        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                registeredNodeCreate(REGISTERED_NODE)
                        .adminKey(ADMIN_KEY)
                        .serviceEndpoints(DEFAULT_ENDPOINTS)
                        .hasKnownStatus(SUCCESS),
                overriding("nodes.registeredNodesEnabled", "false"),
                overriding("fees.simpleFeesEnabled", "true"),
                registeredNodeUpdate(REGISTERED_NODE)
                        .description("updated")
                        .signedBy(DEFAULT_PAYER, ADMIN_KEY)
                        .hasPrecheck(NOT_SUPPORTED));
    }

    @LeakyHapiTest(overrides = {"nodes.registeredNodesEnabled", "fees.simpleFeesEnabled"})
    @DisplayName("delete fails with NOT_SUPPORTED when registeredNodesEnabled is false and simple fees enabled")
    final Stream<DynamicTest> deleteFailsWhenFeatureDisabledWithSimpleFees() {
        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                registeredNodeCreate(REGISTERED_NODE)
                        .adminKey(ADMIN_KEY)
                        .serviceEndpoints(DEFAULT_ENDPOINTS)
                        .hasKnownStatus(SUCCESS),
                overriding("nodes.registeredNodesEnabled", "false"),
                overriding("fees.simpleFeesEnabled", "true"),
                registeredNodeDelete(REGISTERED_NODE)
                        .signedBy(DEFAULT_PAYER, ADMIN_KEY)
                        .hasPrecheck(NOT_SUPPORTED));
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
                atomicBatch(registeredNodeCreate(REGISTERED_NODE)
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

    @HapiTest
    @DisplayName("atomic batch with update + create assigns correct receipt IDs")
    final Stream<DynamicTest> atomicBatchUpdateAndCreateReceiptIds() {
        final var batchOperator = "batchOperator";
        final var createdId = new AtomicLong();
        return hapiTest(
                cryptoCreate(batchOperator).balance(ONE_MILLION_HBARS),
                newKeyNamed(ADMIN_KEY),
                // Pre-create a registered node
                registeredNodeCreate(REGISTERED_NODE)
                        .adminKey(ADMIN_KEY)
                        .serviceEndpoints(DEFAULT_ENDPOINTS)
                        .exposingCreatedIdTo(createdId::set)
                        .hasKnownStatus(SUCCESS),
                // Atomic batch: update existing + create new
                withOpContext((spec, opLog) -> {
                    final var batch = atomicBatch(
                                    registeredNodeUpdate(createdId::get)
                                            .description("updated")
                                            .signedBy(DEFAULT_PAYER, ADMIN_KEY)
                                            .batchKey(batchOperator),
                                    registeredNodeCreate("newRegisteredNode")
                                            .adminKey(ADMIN_KEY)
                                            .serviceEndpoints(DEFAULT_ENDPOINTS)
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
        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                // Schedule a create and verify execution
                scheduleCreate(
                                "scheduleCreate",
                                registeredNodeCreate(REGISTERED_NODE)
                                        .adminKey(ADMIN_KEY)
                                        .serviceEndpoints(DEFAULT_ENDPOINTS))
                        .alsoSigningWith(ADMIN_KEY)
                        .via("scheduleCreateTxn")
                        .hasKnownStatus(SUCCESS),
                getScheduleInfo("scheduleCreate").isExecuted(),
                // Extract the created registered node ID from the scheduled execution record
                // and manually save it to the registry (scheduleCreate doesn't call updateStateOf)
                withOpContext((spec, opLog) -> {
                    final var scheduledRecord =
                            getTxnRecord("scheduleCreateTxn").scheduled();
                    allRunFor(spec, scheduledRecord);
                    final var registeredNodeId =
                            scheduledRecord.getResponseRecord().getReceipt().getRegisteredNodeId();
                    spec.registry().saveRegisteredNodeId(REGISTERED_NODE, registeredNodeId);
                }),
                // Schedule an update and verify execution
                scheduleCreate(
                                "scheduleUpdate",
                                registeredNodeUpdate(REGISTERED_NODE).description("scheduled-desc"))
                        .alsoSigningWith(ADMIN_KEY)
                        .hasKnownStatus(SUCCESS),
                getScheduleInfo("scheduleUpdate").isExecuted(),
                // Schedule a delete and verify execution
                scheduleCreate("scheduleDelete", registeredNodeDelete(REGISTERED_NODE))
                        .alsoSigningWith(ADMIN_KEY)
                        .hasKnownStatus(SUCCESS),
                getScheduleInfo("scheduleDelete").isExecuted());
    }

    // ─── GovernanceTransactions ────────────────────────────────────

    @LeakyHapiTest(overrides = {"hedera.transaction.maxMemoUtf8Bytes"})
    @DisplayName("create, update, and delete registered node via governance account")
    final Stream<DynamicTest> registeredNodeViaGovernanceAccount() {
        final int OVERSIZED_TXN_SIZE = 130 * 1024; // ~130KB
        final int LARGE_TXN_SIZE = 90 * 1024; // ~90KB
        final String LARGE_SIZE_MEMO = StringUtils.repeat("a", LARGE_TXN_SIZE);
        return hapiTest(
                overriding("hedera.transaction.maxMemoUtf8Bytes", OVERSIZED_TXN_SIZE + ""),
                newKeyNamed(ADMIN_KEY),
                // Governance create
                registeredNodeCreate(REGISTERED_NODE)
                        .memo(LARGE_SIZE_MEMO)
                        .adminKey(ADMIN_KEY)
                        .serviceEndpoints(DEFAULT_ENDPOINTS)
                        .payingWith(GENESIS)
                        .hasKnownStatus(SUCCESS),
                // Governance update
                registeredNodeUpdate(REGISTERED_NODE)
                        .memo(LARGE_SIZE_MEMO)
                        .description("gov-update")
                        .signedBy(DEFAULT_PAYER, ADMIN_KEY)
                        .payingWith(GENESIS)
                        .hasKnownStatus(SUCCESS),
                // Governance delete
                registeredNodeDelete(REGISTERED_NODE)
                        .memo(LARGE_SIZE_MEMO)
                        .signedBy(DEFAULT_PAYER, ADMIN_KEY)
                        .payingWith(GENESIS)
                        .hasKnownStatus(SUCCESS));
    }

    // ─── Endpoint factory helpers ──────────────────────────────────

    private static RegisteredServiceEndpoint blockNodeEndpoint(
            final String domain, final int port, final RegisteredServiceEndpoint.BlockNodeEndpoint.BlockNodeApi api) {
        return RegisteredServiceEndpoint.newBuilder()
                .setDomainName(domain)
                .setPort(port)
                .setBlockNode(RegisteredServiceEndpoint.BlockNodeEndpoint.newBuilder()
                        .setEndpointApi(api)
                        .build())
                .build();
    }

    private static RegisteredServiceEndpoint blockNodeEndpointIp(
            final byte[] ip, final int port, final RegisteredServiceEndpoint.BlockNodeEndpoint.BlockNodeApi api) {
        return RegisteredServiceEndpoint.newBuilder()
                .setIpAddress(ByteString.copyFrom(ip))
                .setPort(port)
                .setBlockNode(RegisteredServiceEndpoint.BlockNodeEndpoint.newBuilder()
                        .setEndpointApi(api)
                        .build())
                .build();
    }

    private static RegisteredServiceEndpoint mirrorNodeEndpoint(final String domain, final int port) {
        return RegisteredServiceEndpoint.newBuilder()
                .setDomainName(domain)
                .setPort(port)
                .setMirrorNode(RegisteredServiceEndpoint.MirrorNodeEndpoint.getDefaultInstance())
                .build();
    }

    private static RegisteredServiceEndpoint rpcRelayEndpoint(final String domain, final int port) {
        return RegisteredServiceEndpoint.newBuilder()
                .setDomainName(domain)
                .setPort(port)
                .setRpcRelay(RegisteredServiceEndpoint.RpcRelayEndpoint.getDefaultInstance())
                .build();
    }

    private static RegisteredServiceEndpoint generalServiceEndpoint(
            final String domain, final int port, final String description) {
        return RegisteredServiceEndpoint.newBuilder()
                .setDomainName(domain)
                .setPort(port)
                .setGeneralService(RegisteredServiceEndpoint.GeneralServiceEndpoint.newBuilder()
                        .setDescription(description)
                        .build())
                .build();
    }
}
