// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip869.batch;

import static com.hedera.node.app.hapi.utils.CommonPbjConverters.toPbj;
import static com.hedera.services.bdd.junit.EmbeddedReason.NEEDS_STATE_ACCESS;
import static com.hedera.services.bdd.junit.TestTags.ATOMIC_BATCH;
import static com.hedera.services.bdd.junit.TestTags.ONLY_EMBEDDED;
import static com.hedera.services.bdd.junit.hedera.embedded.EmbeddedMode.CONCURRENT;
import static com.hedera.services.bdd.junit.hedera.utils.NetworkUtils.endpointFor;
import static com.hedera.services.bdd.spec.HapiPropertySource.asServiceEndpoint;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeUpdate;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.EmbeddedVerbs.viewNode;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.hip869.NodeCreateTest.GRPC_PROXY_ENDPOINT_FQDN;
import static com.hedera.services.bdd.suites.hip869.NodeCreateTest.generateX509Certificates;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.GRPC_WEB_PROXY_NOT_SUPPORTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.EmbeddedHapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyEmbeddedHapiTest;
import com.hedera.services.bdd.junit.TargetEmbeddedMode;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(ATOMIC_BATCH)
@Tag(ONLY_EMBEDDED)
@DisplayName("updateNode")
@TargetEmbeddedMode(CONCURRENT)
@HapiTestLifecycle
class AtomicNodeUpdateTestEmbedded {

    private static List<X509Certificate> gossipCertificates;
    private static final String BATCH_OPERATOR = "batchOperator";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.doAdhoc(cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS));

        gossipCertificates = generateX509Certificates(2);
    }

    @LeakyEmbeddedHapiTest(
            reason = NEEDS_STATE_ACCESS,
            overrides = {"nodes.webProxyEndpointsEnabled"})
    final Stream<DynamicTest> cantUpdateGrpcProxyEndpointIfDisabled() throws CertificateEncodingException {
        final var nodeAccount = "nodeAccount";
        return hapiTest(
                overriding("nodes.webProxyEndpointsEnabled", "false"),
                newKeyNamed("adminKey"),
                cryptoCreate(nodeAccount),
                nodeCreate("testNode", nodeAccount)
                        .adminKey("adminKey")
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded())
                        .withNoWebProxyEndpoint()
                        .description("newNode"),
                viewNode("testNode", node -> assertNull(node.grpcProxyEndpoint())),
                atomicBatch(nodeUpdate("testNode")
                                .grpcProxyEndpoint(toPbj(GRPC_PROXY_ENDPOINT_FQDN))
                                .description("updatedNode")
                                .signedBy("adminKey", DEFAULT_PAYER)
                                .hasKnownStatus(GRPC_WEB_PROXY_NOT_SUPPORTED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
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
        final var nodeAccount = "nodeAccount";
        return hapiTest(
                newKeyNamed("adminKey"),
                newKeyNamed("adminKey2"),
                cryptoCreate(nodeAccount),
                nodeCreate("testNode", nodeAccount)
                        .description("description to be changed")
                        .adminKey("adminKey")
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded()),
                atomicBatch(updateOp.batchKey(BATCH_OPERATOR)).payingWith(BATCH_OPERATOR),
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
        final var nodeAccount = "nodeAccount";
        final var nodeUpdateAccount = "nodeUpdateAccount";
        final var updateOp = nodeUpdate("testNode")
                .adminKey("adminKey2")
                .signedBy(DEFAULT_PAYER, "adminKey", "adminKey2")
                .description("updated description")
                .accountId(nodeUpdateAccount)
                .gossipEndpoint(List.of(
                        asServiceEndpoint("127.0.0.1:60"),
                        asServiceEndpoint("127.0.0.2:60"),
                        asServiceEndpoint("127.0.0.3:60")))
                .serviceEndpoint(List.of(asServiceEndpoint("127.0.1.1:60"), asServiceEndpoint("127.0.1.2:60")))
                .gossipCaCertificate(gossipCertificates.getLast().getEncoded())
                .grpcCertificateHash("grpcCert".getBytes())
                .signedByPayerAnd("adminKey", "adminKey2", nodeUpdateAccount);
        return hapiTest(
                overriding("nodes.updateAccountIdAllowed", "true"),
                newKeyNamed("adminKey"),
                newKeyNamed("adminKey2"),
                cryptoCreate(nodeAccount),
                cryptoCreate(nodeUpdateAccount),
                nodeCreate("testNode", nodeAccount)
                        .description("description to be changed")
                        .adminKey("adminKey")
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded()),
                atomicBatch(updateOp.batchKey(BATCH_OPERATOR)).payingWith(BATCH_OPERATOR),
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
                    assertEquals(
                            toPbj(spec.registry().getAccountID(nodeUpdateAccount)),
                            node.accountId(),
                            "Node accountId should be updated");
                }))));
    }

    @EmbeddedHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> sentinelUnsetsGrpcWebProxyEndpoint() throws CertificateEncodingException {
        final var nodeAccount = "nodeAccount";
        return hapiTest(
                newKeyNamed("adminKey"),
                cryptoCreate(nodeAccount),
                nodeCreate("testNode", nodeAccount)
                        .adminKey("adminKey")
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded())
                        .grpcWebProxyEndpoint(GRPC_PROXY_ENDPOINT_FQDN)
                        .description("newNode"),
                viewNode("testNode", node -> assertNotNull(node.grpcProxyEndpoint())),
                atomicBatch(nodeUpdate("testNode")
                                .grpcProxyEndpoint(com.hedera.hapi.node.base.ServiceEndpoint.DEFAULT)
                                .description("updatedNode")
                                .signedByPayerAnd("adminKey")
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                viewNode("testNode", node -> assertNull(node.grpcProxyEndpoint())));
    }

    @EmbeddedHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> unsetGrpcProxyFieldDoesntEraseExistingGrpcProxy() throws CertificateEncodingException {
        final var nodeAccount = "nodeAccount";
        return hapiTest(
                newKeyNamed("adminKey"),
                cryptoCreate(nodeAccount),
                nodeCreate("testNode", nodeAccount)
                        .adminKey("adminKey")
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded())
                        .grpcWebProxyEndpoint(GRPC_PROXY_ENDPOINT_FQDN)
                        .description("newNode"),
                viewNode("testNode", node -> assertNotNull(node.grpcProxyEndpoint())),
                atomicBatch(nodeUpdate("testNode")
                                .description("arbitrary update of something other than the grpc proxy endpoint")
                                .signedByPayerAnd("adminKey")
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                viewNode("testNode", node -> assertNotNull(node.grpcProxyEndpoint())));
    }
}
