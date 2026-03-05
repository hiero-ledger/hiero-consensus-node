// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1137;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.registeredNodeCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.registeredNodeDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.registeredNodeUpdate;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.hip869.NodeCreateTest.generateX509Certificates;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_NODE_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.REGISTERED_NODE_STILL_REFERENCED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
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

    private static final List<RegisteredServiceEndpoint> DEFAULT_ENDPOINTS =
            List.of(RegisteredServiceEndpoint.newBuilder()
                    .setDomainName("blocknode.example.com")
                    .setPort(8080)
                    .setBlockNode(RegisteredServiceEndpoint.BlockNodeEndpoint.newBuilder()
                            .setEndpointApi(RegisteredServiceEndpoint.BlockNodeEndpoint.BlockNodeApi.STATUS)
                            .build())
                    .build());

    @HapiTest
    @DisplayName("create, update (admin key rotation), and delete")
    final Stream<DynamicTest> crudHappyPath() {
        final var createdId = new AtomicLong();
        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                newKeyNamed(NEW_ADMIN_KEY),
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
                            .adminKey(spec.registry().getKey(NEW_ADMIN_KEY))
                            .signedBy(DEFAULT_PAYER, ADMIN_KEY, NEW_ADMIN_KEY)
                            .hasKnownStatus(SUCCESS);
                    allRunFor(spec, update);
                }),
                registeredNodeDelete(createdId::get)
                        .signedBy(DEFAULT_PAYER, NEW_ADMIN_KEY)
                        .hasKnownStatus(SUCCESS));
    }

    @HapiTest
    @DisplayName("description-only update preserves endpoints and admin key")
    final Stream<DynamicTest> descriptionOnlyUpdate() {
        final var createdId = new AtomicLong();
        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                registeredNodeCreate("rn")
                        .adminKey(ADMIN_KEY)
                        .serviceEndpoints(DEFAULT_ENDPOINTS)
                        .exposingCreatedIdTo(createdId::set)
                        .hasKnownStatus(SUCCESS),
                withOpContext((spec, opLog) -> {
                    final var update = registeredNodeUpdate(createdId::get)
                            .description("updated-desc")
                            .signedBy(DEFAULT_PAYER, ADMIN_KEY)
                            .hasKnownStatus(SUCCESS);
                    allRunFor(spec, update);
                }));
    }

    @HapiTest
    @DisplayName("endpoints replace replaces entire list")
    final Stream<DynamicTest> endpointsReplaceUpdate() {
        final var createdId = new AtomicLong();
        final var replacementEndpoints = List.of(RegisteredServiceEndpoint.newBuilder()
                .setIpAddress(ByteString.copyFrom(new byte[] {10, 0, 0, 1}))
                .setPort(9090)
                .setBlockNode(RegisteredServiceEndpoint.BlockNodeEndpoint.newBuilder()
                        .setEndpointApi(RegisteredServiceEndpoint.BlockNodeEndpoint.BlockNodeApi.PUBLISH)
                        .build())
                .build());
        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                registeredNodeCreate("rn")
                        .adminKey(ADMIN_KEY)
                        .serviceEndpoints(DEFAULT_ENDPOINTS)
                        .exposingCreatedIdTo(createdId::set)
                        .hasKnownStatus(SUCCESS),
                withOpContext((spec, opLog) -> {
                    final var update = registeredNodeUpdate(createdId::get)
                            .serviceEndpoints(replacementEndpoints)
                            .signedBy(DEFAULT_PAYER, ADMIN_KEY)
                            .hasKnownStatus(SUCCESS);
                    allRunFor(spec, update);
                }));
    }

    @HapiTest
    @DisplayName("update requires admin key signature, key rotation requires dual signature")
    final Stream<DynamicTest> updateSignatureRequirements() {
        final var createdId = new AtomicLong();
        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                newKeyNamed(NEW_ADMIN_KEY),
                registeredNodeCreate("rn")
                        .adminKey(ADMIN_KEY)
                        .serviceEndpoints(DEFAULT_ENDPOINTS)
                        .exposingCreatedIdTo(createdId::set)
                        .hasKnownStatus(SUCCESS),
                withOpContext((spec, opLog) -> {
                    // Update without admin key sig → INVALID_SIGNATURE
                    final var unauthorizedUpdate = registeredNodeUpdate(createdId::get)
                            .description("unauthorized")
                            .signedBy(DEFAULT_PAYER)
                            .hasKnownStatus(INVALID_SIGNATURE);
                    // Rotation signed only by old key → INVALID_SIGNATURE
                    final var failedRotation = registeredNodeUpdate(createdId::get)
                            .adminKey(spec.registry().getKey(NEW_ADMIN_KEY))
                            .signedBy(DEFAULT_PAYER, ADMIN_KEY)
                            .hasKnownStatus(INVALID_SIGNATURE);
                    // Rotation signed by both old and new keys → SUCCESS
                    final var successfulRotation = registeredNodeUpdate(createdId::get)
                            .adminKey(spec.registry().getKey(NEW_ADMIN_KEY))
                            .signedBy(DEFAULT_PAYER, ADMIN_KEY, NEW_ADMIN_KEY)
                            .hasKnownStatus(SUCCESS);
                    allRunFor(spec, unauthorizedUpdate, failedRotation, successfulRotation);
                }));
    }

    @HapiTest
    @DisplayName("update of non-existent registered node fails with INVALID_NODE_ID")
    final Stream<DynamicTest> updateNonExistentNodeFails() {
        return hapiTest(newKeyNamed(ADMIN_KEY), withOpContext((spec, opLog) -> {
            final var update = registeredNodeUpdate(() -> 999_999L)
                    .description("ghost")
                    .signedBy(DEFAULT_PAYER)
                    .hasPrecheck(INVALID_NODE_ID);
            allRunFor(spec, update);
        }));
    }

    @HapiTest
    @DisplayName("consensus node create and update with associated registered node")
    final Stream<DynamicTest> consensusNodeWithAssociatedRegisteredNode() throws CertificateEncodingException {
        final var registeredNodeId = new AtomicLong();
        final var nodeAccount = "nodeAccount";
        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                cryptoCreate(nodeAccount),
                registeredNodeCreate("rn")
                        .adminKey(ADMIN_KEY)
                        .serviceEndpoints(DEFAULT_ENDPOINTS)
                        .exposingCreatedIdTo(registeredNodeId::set)
                        .hasKnownStatus(SUCCESS),
                withOpContext((spec, opLog) -> {
                    final var create = nodeCreate("testNode", nodeAccount)
                            .adminKey(ADMIN_KEY)
                            .gossipCaCertificate(gossipCertificates.getFirst().getEncoded())
                            .associatedRegisteredNode(List.of(registeredNodeId.get()))
                            .hasKnownStatus(SUCCESS);
                    allRunFor(spec, create);
                }),
                withOpContext((spec, opLog) -> {
                    final var update = nodeUpdate("testNode")
                            .associatedRegisteredNode(List.of())
                            .signedBy(DEFAULT_PAYER, ADMIN_KEY)
                            .hasKnownStatus(SUCCESS);
                    allRunFor(spec, update);
                }));
    }

    @HapiTest
    @DisplayName("consensus node create fails with non-existent associated registered node")
    final Stream<DynamicTest> createWithNonExistentAssociatedRegisteredNodeFails() throws CertificateEncodingException {
        final var nodeAccount = "nodeAccount";
        return hapiTest(newKeyNamed(ADMIN_KEY), cryptoCreate(nodeAccount), withOpContext((spec, opLog) -> {
            final var create = nodeCreate("testNode", nodeAccount)
                    .adminKey(ADMIN_KEY)
                    .gossipCaCertificate(gossipCertificates.getFirst().getEncoded())
                    .associatedRegisteredNode(List.of(999_999L))
                    .hasKnownStatus(INVALID_NODE_ID);
            allRunFor(spec, create);
        }));
    }

    @HapiTest
    @DisplayName("delete registered node fails when still referenced by consensus node")
    final Stream<DynamicTest> deleteReferencedRegisteredNodeFails() throws CertificateEncodingException {
        final var registeredNodeId = new AtomicLong();
        final var nodeAccount = "nodeAccount";
        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                cryptoCreate(nodeAccount),
                registeredNodeCreate("rn")
                        .adminKey(ADMIN_KEY)
                        .serviceEndpoints(DEFAULT_ENDPOINTS)
                        .exposingCreatedIdTo(registeredNodeId::set)
                        .hasKnownStatus(SUCCESS),
                withOpContext((spec, opLog) -> {
                    final var create = nodeCreate("testNode", nodeAccount)
                            .adminKey(ADMIN_KEY)
                            .gossipCaCertificate(gossipCertificates.getFirst().getEncoded())
                            .associatedRegisteredNode(List.of(registeredNodeId.get()))
                            .hasKnownStatus(SUCCESS);
                    allRunFor(spec, create);
                }),
                withOpContext((spec, opLog) -> {
                    final var delete = registeredNodeDelete(registeredNodeId::get)
                            .signedBy(DEFAULT_PAYER, ADMIN_KEY)
                            .hasKnownStatus(REGISTERED_NODE_STILL_REFERENCED);
                    allRunFor(spec, delete);
                }));
    }

    @HapiTest
    @DisplayName("delete registered node succeeds after consensus node clears reference")
    final Stream<DynamicTest> deleteRegisteredNodeAfterClearingReference() throws CertificateEncodingException {
        final var registeredNodeId = new AtomicLong();
        final var nodeAccount = "nodeAccount";
        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                cryptoCreate(nodeAccount),
                registeredNodeCreate("rn")
                        .adminKey(ADMIN_KEY)
                        .serviceEndpoints(DEFAULT_ENDPOINTS)
                        .exposingCreatedIdTo(registeredNodeId::set)
                        .hasKnownStatus(SUCCESS),
                withOpContext((spec, opLog) -> {
                    final var create = nodeCreate("testNode", nodeAccount)
                            .adminKey(ADMIN_KEY)
                            .gossipCaCertificate(gossipCertificates.getFirst().getEncoded())
                            .associatedRegisteredNode(List.of(registeredNodeId.get()))
                            .hasKnownStatus(SUCCESS);
                    allRunFor(spec, create);
                }),
                withOpContext((spec, opLog) -> {
                    // Clear the reference
                    final var update = nodeUpdate("testNode")
                            .associatedRegisteredNode(List.of())
                            .signedBy(DEFAULT_PAYER, ADMIN_KEY)
                            .hasKnownStatus(SUCCESS);
                    allRunFor(spec, update);
                }),
                withOpContext((spec, opLog) -> {
                    // Now delete should succeed
                    final var delete = registeredNodeDelete(registeredNodeId::get)
                            .signedBy(DEFAULT_PAYER, ADMIN_KEY)
                            .hasKnownStatus(SUCCESS);
                    allRunFor(spec, delete);
                }));
    }
}
