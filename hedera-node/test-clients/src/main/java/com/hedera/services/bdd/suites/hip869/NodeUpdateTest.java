// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip869;

import static com.hedera.node.app.hapi.utils.CommonPbjConverters.toPbj;
import static com.hedera.services.bdd.junit.EmbeddedReason.NEEDS_STATE_ACCESS;
import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.ONLY_SUBPROCESS;
import static com.hedera.services.bdd.junit.hedera.utils.AddressBookUtils.CLASSIC_FIRST_NODE_ACCOUNT_NUM;
import static com.hedera.services.bdd.junit.hedera.utils.AddressBookUtils.endpointFor;
import static com.hedera.services.bdd.spec.HapiPropertySource.asAccount;
import static com.hedera.services.bdd.spec.HapiPropertySource.asDnsServiceEndpoint;
import static com.hedera.services.bdd.spec.HapiPropertySource.asServiceEndpoint;
import static com.hedera.services.bdd.spec.HapiPropertySource.invalidServiceEndpoint;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.WRONG_LENGTH_EDDSA_KEY;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeUpdate;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.EmbeddedVerbs.viewNode;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ADDRESS_BOOK_CONTROL;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.NONSENSE_KEY;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.hip869.NodeCreateTest.GRPC_PROXY_ENDPOINT_FQDN;
import static com.hedera.services.bdd.suites.hip869.NodeCreateTest.GRPC_PROXY_ENDPOINT_IP;
import static com.hedera.services.bdd.suites.hip869.NodeCreateTest.generateX509Certificates;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.GOSSIP_ENDPOINTS_EXCEEDED_LIMIT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.GOSSIP_ENDPOINT_CANNOT_HAVE_FQDN;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.GRPC_WEB_PROXY_NOT_SUPPORTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ADMIN_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_GOSSIP_CA_CERTIFICATE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_IPV4_ADDRESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_NODE_ACCOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_NODE_DESCRIPTION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_NODE_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SERVICE_ENDPOINT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.KEY_REQUIRED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.NOT_SUPPORTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.RECORD_NOT_FOUND;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SERVICE_ENDPOINTS_EXCEEDED_LIMIT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.UPDATE_NODE_ACCOUNT_NOT_ALLOWED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.EmbeddedHapiTest;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyEmbeddedHapiTest;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hederahashgraph.api.proto.java.AccountID;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@DisplayName("updateNode")
@HapiTestLifecycle
public class NodeUpdateTest {
    private static List<X509Certificate> gossipCertificates;

    @BeforeAll
    static void beforeAll() {
        gossipCertificates = generateX509Certificates(2);
    }

    @HapiTest
    @DisplayName("cannot update a negative nodeid")
    final Stream<DynamicTest> cannotUpdateNegativeNodeId() {
        return hapiTest(nodeUpdate("-1").hasPrecheck(INVALID_NODE_ID));
    }

    @HapiTest
    @DisplayName("cannot update a missing nodeid")
    final Stream<DynamicTest> updateMissingNodeFail() {
        return hapiTest(nodeUpdate("100").hasPrecheck(INVALID_NODE_ID));
    }

    @HapiTest
    @DisplayName("cannot update a deleted node")
    final Stream<DynamicTest> updateDeletedNodeFail() throws CertificateEncodingException {
        return hapiTest(
                newKeyNamed("adminKey"),
                nodeCreate("testNode")
                        .adminKey("adminKey")
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded()),
                nodeDelete("testNode"),
                nodeUpdate("testNode").hasPrecheck(INVALID_NODE_ID));
    }

    @HapiTest
    final Stream<DynamicTest> validateAdminKey() throws CertificateEncodingException {
        return hapiTest(
                newKeyNamed("adminKey"),
                nodeCreate("testNode")
                        .adminKey("adminKey")
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded()),
                nodeUpdate("testNode").adminKey(NONSENSE_KEY).hasPrecheck(KEY_REQUIRED),
                nodeUpdate("testNode")
                        .adminKey(WRONG_LENGTH_EDDSA_KEY)
                        .signedBy(GENESIS)
                        .hasPrecheck(INVALID_ADMIN_KEY));
    }

    @HapiTest
    final Stream<DynamicTest> updateEmptyGossipCaCertificateFail() throws CertificateEncodingException {
        return hapiTest(
                newKeyNamed("adminKey"),
                nodeCreate("testNode")
                        .adminKey("adminKey")
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded()),
                nodeUpdate("testNode").gossipCaCertificate(new byte[0]).hasPrecheck(INVALID_GOSSIP_CA_CERTIFICATE));
    }

    @HapiTest
    final Stream<DynamicTest> updateAccountIdNotAllowed() throws CertificateEncodingException {
        return hapiTest(
                newKeyNamed("adminKey"),
                nodeCreate("testNode")
                        .adminKey("adminKey")
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded()),
                nodeUpdate("testNode").accountId("0.0.100").hasPrecheck(UPDATE_NODE_ACCOUNT_NOT_ALLOWED));
    }

    @Tag(ONLY_SUBPROCESS)
    @LeakyHapiTest(overrides = {"nodes.updateAccountIdAllowed"})
    final Stream<DynamicTest> updateAccountIdAndSubmitWithOld() {
        final var nodeIdToUpdate = 1;
        final var oldNodeAccountId = nodeIdToUpdate + CLASSIC_FIRST_NODE_ACCOUNT_NUM;
        return hapiTest(
                overriding("nodes.updateAccountIdAllowed", "true"),
                cryptoCreate("newNodeAccount"),
                // Node update works with nodeId not accountId,
                // so we are updating the node we are submitting to
                nodeUpdate(String.valueOf(nodeIdToUpdate))
                        .accountId("newNodeAccount")
                        .payingWith(DEFAULT_PAYER)
                        .signedByPayerAnd(GENESIS),
                cryptoCreate("foo")
                        .setNode(oldNodeAccountId)
                        .hasPrecheck(INVALID_NODE_ACCOUNT)
                        .via("createTxn"),
                // Assert that the transaction was not submitted and failed on ingest
                getTxnRecord("createTxn").hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
    }

    @LeakyEmbeddedHapiTest(
            reason = {NEEDS_STATE_ACCESS},
            overrides = {"nodes.updateAccountIdAllowed"})
    final Stream<DynamicTest> accountIdGetsUpdatedCorrectly() {
        final AtomicReference<AccountID> initialAccountId = new AtomicReference<>();
        final AtomicReference<AccountID> newAccountId = new AtomicReference<>();
        return hapiTest(
                overriding("nodes.updateAccountIdAllowed", "true"),
                newKeyNamed("adminKey"),
                cryptoCreate("initialNodeAccount").exposingCreatedIdTo(initialAccountId::set),
                cryptoCreate("newNodeAccount").exposingCreatedIdTo(newAccountId::set),
                sourcing(() -> {
                    try {
                        return nodeCreate("testNode")
                                .adminKey("adminKey")
                                .accountId(initialAccountId.get())
                                .gossipCaCertificate(
                                        gossipCertificates.getFirst().getEncoded());
                    } catch (CertificateEncodingException e) {
                        throw new RuntimeException(e);
                    }
                }),
                sourcing(() -> nodeUpdate("testNode")
                        .accountId("newNodeAccount")
                        .signedByPayerAnd("newNodeAccount", "adminKey")),
                sourcing(() -> viewNode("testNode", node -> {
                    assertNotNull(node.accountId(), "Node accountId should not be null");
                    assertNotNull(node.accountId().accountNum(), "Node accountNum should not be null");
                    assertEquals(
                            node.accountId().accountNum(), newAccountId.get().getAccountNum());
                })));
    }

    @Disabled // enable when handling in-flight accountId update is supported
    @Tag(ONLY_SUBPROCESS)
    @LeakyHapiTest(overrides = {"nodes.updateAccountIdAllowed"})
    final Stream<DynamicTest> accountIdUpdateInBatch() {
        final var nodeIdToUpdate = 1;
        final var oldNodeAccountId = nodeIdToUpdate + CLASSIC_FIRST_NODE_ACCOUNT_NUM;
        return hapiTest(
                overriding("nodes.updateAccountIdAllowed", "true"),
                cryptoCreate("batchOperator"),
                cryptoCreate("newNodeAccount"),
                atomicBatch(
                                nodeUpdate(String.valueOf(nodeIdToUpdate))
                                        .accountId("newNodeAccount")
                                        .payingWith(DEFAULT_PAYER)
                                        .signedByPayerAnd(GENESIS)
                                        .batchKey("batchOperator"),
                                // check that the transaction after update passes,when the nodeAccountId is updated
                                cryptoCreate("foo").batchKey("batchOperator").via("createTxn"))
                        // Setting nodeId on the batch transaction
                        // automatically sets it for the inner transactions as well
                        .setNode(oldNodeAccountId)
                        .payingWith("batchOperator"));
    }

    @HapiTest
    final Stream<DynamicTest> validateGossipEndpoint() throws CertificateEncodingException {
        return hapiTest(
                newKeyNamed("adminKey"),
                nodeCreate("testNode")
                        .adminKey("adminKey")
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded()),
                nodeUpdate("testNode")
                        .adminKey("adminKey")
                        .gossipEndpoint(List.of(asServiceEndpoint("127.0.0.2:60"), invalidServiceEndpoint()))
                        .hasKnownStatus(INVALID_IPV4_ADDRESS),
                nodeUpdate("testNode")
                        .adminKey("adminKey")
                        .gossipEndpoint(List.of(asServiceEndpoint("127.0.0.3:60"), asDnsServiceEndpoint("test.dom:10")))
                        .hasKnownStatus(GOSSIP_ENDPOINT_CANNOT_HAVE_FQDN));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> validateServiceEndpoint() throws CertificateEncodingException {
        return hapiTest(
                newKeyNamed("adminKey"),
                nodeCreate("testNode")
                        .adminKey("adminKey")
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded()),
                nodeUpdate("testNode")
                        .adminKey("adminKey")
                        .serviceEndpoint(List.of(asServiceEndpoint("127.0.0.2:60"), invalidServiceEndpoint()))
                        .hasKnownStatus(INVALID_IPV4_ADDRESS));
    }

    @HapiTest
    final Stream<DynamicTest> validateGrpcProxyEndpoint() throws CertificateEncodingException {
        return hapiTest(
                newKeyNamed("adminKey"),
                nodeCreate("testNode")
                        .adminKey("adminKey")
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded()),
                nodeUpdate("testNode")
                        .adminKey("adminKey")
                        .grpcProxyEndpoint(invalidServiceEndpoint())
                        .hasKnownStatus(INVALID_SERVICE_ENDPOINT));
    }

    @LeakyEmbeddedHapiTest(
            reason = NEEDS_STATE_ACCESS,
            overrides = {"nodes.webProxyEndpointsEnabled"})
    final Stream<DynamicTest> cantUpdateGrpcProxyEndpointIfDisabled() throws CertificateEncodingException {
        return hapiTest(
                overriding("nodes.webProxyEndpointsEnabled", "false"),
                newKeyNamed("adminKey"),
                nodeCreate("testNode")
                        .adminKey("adminKey")
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded())
                        .withNoWebProxyEndpoint()
                        .description("newNode"),
                viewNode("testNode", node -> assertNull(node.grpcProxyEndpoint())),
                nodeUpdate("testNode")
                        .grpcProxyEndpoint(toPbj(GRPC_PROXY_ENDPOINT_FQDN))
                        .description("updatedNode")
                        .signedBy("adminKey", DEFAULT_PAYER)
                        .hasKnownStatus(GRPC_WEB_PROXY_NOT_SUPPORTED));
    }

    @EmbeddedHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> updateMultipleFieldsWork() throws CertificateEncodingException {
        final var proxyWebEndpoint = toPbj(endpointFor("grpc.web.proxy.com", 123));
        final var updateOp = nodeUpdate("testNode")
                .adminKey("adminKey2")
                .signedBy(DEFAULT_PAYER, "adminKey", "adminKey2")
                .description("updated description")
                .gossipEndpoint(List.of(
                        asServiceEndpoint("127.0.0.1:60"),
                        asServiceEndpoint("127.0.0.2:60"),
                        asServiceEndpoint("127.0.0.3:60")))
                .grpcProxyEndpoint(proxyWebEndpoint)
                .serviceEndpoint(List.of(asServiceEndpoint("127.0.1.1:60"), asServiceEndpoint("127.0.1.2:60")))
                .gossipCaCertificate(gossipCertificates.getLast().getEncoded())
                .grpcCertificateHash("grpcCert".getBytes());
        return hapiTest(
                newKeyNamed("adminKey"),
                newKeyNamed("adminKey2"),
                nodeCreate("testNode")
                        .description("description to be changed")
                        .adminKey("adminKey")
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded()),
                updateOp,
                viewNode("testNode", node -> {
                    assertEquals("updated description", node.description(), "Node description should be updated");
                    assertIterableEquals(
                            List.of(
                                    asServiceEndpoint("127.0.0.1:60"),
                                    asServiceEndpoint("127.0.0.2:60"),
                                    asServiceEndpoint("127.0.0.3:60")),
                            node.gossipEndpoint(),
                            "Node gossipEndpoint should be updated");
                    assertIterableEquals(
                            List.of(asServiceEndpoint("127.0.1.1:60"), asServiceEndpoint("127.0.1.2:60")),
                            node.serviceEndpoint(),
                            "Node serviceEndpoint should be updated");
                    assertEquals(proxyWebEndpoint, node.grpcProxyEndpoint());
                    try {
                        assertEquals(
                                Bytes.wrap(gossipCertificates.getLast().getEncoded()),
                                node.gossipCaCertificate(),
                                "Node gossipCaCertificate should be updated");
                    } catch (CertificateEncodingException e) {
                        throw new RuntimeException(e);
                    }
                    assertEquals(
                            Bytes.wrap("grpcCert"),
                            node.grpcCertificateHash(),
                            "Node grpcCertificateHash should be updated");
                    assertEquals(toPbj(updateOp.getAdminKey()), node.adminKey(), "Node adminKey should be updated");
                }));
    }

    @LeakyEmbeddedHapiTest(
            reason = NEEDS_STATE_ACCESS,
            overrides = {"nodes.updateAccountIdAllowed"})
    final Stream<DynamicTest> updateAccountIdWork() throws CertificateEncodingException {
        final var updateOp = nodeUpdate("testNode")
                .adminKey("adminKey2")
                .signedBy(DEFAULT_PAYER, "adminKey", "adminKey2")
                .description("updated description")
                .accountId("100")
                .gossipEndpoint(List.of(
                        asServiceEndpoint("127.0.0.1:60"),
                        asServiceEndpoint("127.0.0.2:60"),
                        asServiceEndpoint("127.0.0.3:60")))
                .serviceEndpoint(List.of(asServiceEndpoint("127.0.1.1:60"), asServiceEndpoint("127.0.1.2:60")))
                .gossipCaCertificate(gossipCertificates.getLast().getEncoded())
                .grpcCertificateHash("grpcCert".getBytes());
        return hapiTest(
                overriding("nodes.updateAccountIdAllowed", "true"),
                newKeyNamed("adminKey"),
                newKeyNamed("adminKey2"),
                nodeCreate("testNode")
                        .description("description to be changed")
                        .adminKey("adminKey")
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded()),
                updateOp,
                withOpContext((spec, log) -> allRunFor(spec, viewNode("testNode", node -> {
                    assertEquals("updated description", node.description(), "Node description should be updated");
                    assertIterableEquals(
                            List.of(
                                    asServiceEndpoint("127.0.0.1:60"),
                                    asServiceEndpoint("127.0.0.2:60"),
                                    asServiceEndpoint("127.0.0.3:60")),
                            node.gossipEndpoint(),
                            "Node gossipEndpoint should be updated");
                    assertIterableEquals(
                            List.of(asServiceEndpoint("127.0.1.1:60"), asServiceEndpoint("127.0.1.2:60")),
                            node.serviceEndpoint(),
                            "Node serviceEndpoint should be updated");
                    try {
                        assertEquals(
                                Bytes.wrap(gossipCertificates.getLast().getEncoded()),
                                node.gossipCaCertificate(),
                                "Node gossipCaCertificate should be updated");
                    } catch (CertificateEncodingException e) {
                        throw new RuntimeException(e);
                    }
                    assertEquals(
                            Bytes.wrap("grpcCert"),
                            node.grpcCertificateHash(),
                            "Node grpcCertificateHash should be updated");
                    assertEquals(toPbj(updateOp.getAdminKey()), node.adminKey(), "Node adminKey should be updated");
                    assertEquals(toPbj(asAccount(spec, 100)), node.accountId(), "Node accountId should be updated");
                }))));
    }

    @HapiTest
    final Stream<DynamicTest> failsAtIngestForUnAuthorizedTxns() throws CertificateEncodingException {
        final String description = "His vorpal blade went snicker-snack!";
        return hapiTest(
                newKeyNamed("adminKey"),
                cryptoCreate("payer").balance(10_000_000_000L),
                nodeCreate("ntb")
                        .adminKey("adminKey")
                        .description(description)
                        .fee(ONE_HBAR)
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded())
                        .via("nodeCreation"),
                nodeUpdate("ntb")
                        .payingWith("payer")
                        .accountId("0.0.1000")
                        .hasPrecheck(UPDATE_NODE_ACCOUNT_NOT_ALLOWED)
                        .fee(ONE_HBAR)
                        .via("updateNode"));
    }

    @LeakyHapiTest(overrides = {"nodes.maxServiceEndpoint"})
    final Stream<DynamicTest> validateServiceEndpointSize() throws CertificateEncodingException {
        return hapiTest(
                overriding("nodes.maxServiceEndpoint", "2"),
                newKeyNamed("adminKey"),
                nodeCreate("testNode")
                        .adminKey("adminKey")
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded()),
                nodeUpdate("testNode")
                        .adminKey("adminKey")
                        .serviceEndpoint(List.of(
                                asServiceEndpoint("127.0.0.1:60"),
                                asServiceEndpoint("127.0.0.2:60"),
                                asServiceEndpoint("127.0.0.3:60")))
                        .hasKnownStatus(SERVICE_ENDPOINTS_EXCEEDED_LIMIT));
    }

    @LeakyHapiTest(overrides = {"nodes.maxGossipEndpoint"})
    final Stream<DynamicTest> validateGossipEndpointSize() throws CertificateEncodingException {
        return hapiTest(
                overriding("nodes.maxGossipEndpoint", "2"),
                newKeyNamed("adminKey"),
                nodeCreate("testNode")
                        .adminKey("adminKey")
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded()),
                nodeUpdate("testNode")
                        .adminKey("adminKey")
                        .gossipEndpoint(List.of(
                                asServiceEndpoint("127.0.0.1:60"),
                                asServiceEndpoint("127.0.0.2:60"),
                                asServiceEndpoint("127.0.0.3:60")))
                        .hasKnownStatus(GOSSIP_ENDPOINTS_EXCEEDED_LIMIT));
    }

    @EmbeddedHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> sentinelUnsetsGrpcWebProxyEndpoint() throws CertificateEncodingException {
        return hapiTest(
                newKeyNamed("adminKey"),
                nodeCreate("testNode")
                        .adminKey("adminKey")
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded())
                        .grpcWebProxyEndpoint(GRPC_PROXY_ENDPOINT_FQDN)
                        .description("newNode"),
                viewNode("testNode", node -> assertNotNull(node.grpcProxyEndpoint())),
                nodeUpdate("testNode")
                        .grpcProxyEndpoint(ServiceEndpoint.DEFAULT)
                        .description("updatedNode")
                        .signedByPayerAnd("adminKey"),
                viewNode("testNode", node -> assertNull(node.grpcProxyEndpoint())));
    }

    @EmbeddedHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> unsetGrpcProxyFieldDoesntEraseExistingGrpcProxy() throws CertificateEncodingException {
        return hapiTest(
                newKeyNamed("adminKey"),
                nodeCreate("testNode")
                        .adminKey("adminKey")
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded())
                        .grpcWebProxyEndpoint(GRPC_PROXY_ENDPOINT_FQDN)
                        .description("newNode"),
                viewNode("testNode", node -> assertNotNull(node.grpcProxyEndpoint())),
                nodeUpdate("testNode")
                        .description("arbitrary update of something other than the grpc proxy endpoint")
                        .signedByPayerAnd("adminKey"),
                viewNode("testNode", node -> assertNotNull(node.grpcProxyEndpoint())));
    }

    @LeakyHapiTest(overrides = {"nodes.nodeMaxDescriptionUtf8Bytes"})
    final Stream<DynamicTest> updateTooLargeDescriptionFail() throws CertificateEncodingException {
        return hapiTest(
                overriding("nodes.nodeMaxDescriptionUtf8Bytes", "3"),
                newKeyNamed("adminKey"),
                nodeCreate("testNode")
                        .adminKey("adminKey")
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded()),
                nodeUpdate("testNode")
                        .adminKey("adminKey")
                        .description("toolarge")
                        .hasKnownStatus(INVALID_NODE_DESCRIPTION));
    }

    @LeakyHapiTest(overrides = {"nodes.enableDAB"})
    @DisplayName("DAB enable test")
    final Stream<DynamicTest> checkDABEnable() throws CertificateEncodingException {
        return hapiTest(
                newKeyNamed("adminKey"),
                nodeCreate("testNode")
                        .adminKey("adminKey")
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded()),
                overriding("nodes.enableDAB", "false"),
                nodeUpdate("testNode")
                        .adminKey("adminKey")
                        .serviceEndpoint(List.of(asServiceEndpoint("127.0.0.2:60"), invalidServiceEndpoint()))
                        .hasPrecheck(NOT_SUPPORTED));
    }

    @HapiTest
    final Stream<DynamicTest> signedByCouncilNotAdminKeyFail() throws CertificateEncodingException {
        return hapiTest(
                newKeyNamed("adminKey"),
                nodeCreate("testNode")
                        .adminKey("adminKey")
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded()),
                nodeUpdate("testNode").signedBy(ADDRESS_BOOK_CONTROL).hasPrecheck(INVALID_SIGNATURE));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> signedByAdminKeySuccess() throws CertificateEncodingException {
        return hapiTest(
                newKeyNamed("adminKey"),
                cryptoCreate("payer").balance(10_000_000_000L),
                nodeCreate("testNode")
                        .adminKey("adminKey")
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded()),
                nodeUpdate("testNode")
                        .payingWith("payer")
                        .signedBy("payer", "adminKey")
                        .description("updated description")
                        .via("successUpdate"),
                getTxnRecord("successUpdate").logged());
    }

    @HapiTest
    final Stream<DynamicTest> webProxyAsIpAddressIsRejected() throws CertificateEncodingException {
        return hapiTest(
                newKeyNamed("adminKey"),
                nodeCreate("testNode")
                        .adminKey("adminKey")
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded()),
                nodeUpdate("testNode")
                        .signedByPayerAnd("adminKey")
                        .grpcProxyEndpoint(toPbj(GRPC_PROXY_ENDPOINT_IP))
                        .hasKnownStatus(INVALID_SERVICE_ENDPOINT));
    }
}
