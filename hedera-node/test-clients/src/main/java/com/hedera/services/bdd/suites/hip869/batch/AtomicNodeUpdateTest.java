// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip869.batch;

import static com.hedera.services.bdd.junit.TestTags.ATOMIC_BATCH;
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
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.suites.HapiSuite.ADDRESS_BOOK_CONTROL;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.NONSENSE_KEY;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.hip869.NodeCreateTest.GRPC_PROXY_ENDPOINT_IP;
import static com.hedera.services.bdd.suites.hip869.NodeCreateTest.generateX509Certificates;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.GOSSIP_ENDPOINTS_EXCEEDED_LIMIT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.GOSSIP_ENDPOINT_CANNOT_HAVE_FQDN;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ADMIN_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_GOSSIP_CA_CERTIFICATE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_IPV4_ADDRESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_NODE_DESCRIPTION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_NODE_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SERVICE_ENDPOINT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.KEY_REQUIRED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.NOT_SUPPORTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SERVICE_ENDPOINTS_EXCEEDED_LIMIT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.UPDATE_NODE_ACCOUNT_NOT_ALLOWED;

import com.hedera.node.app.hapi.utils.CommonPbjConverters;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
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
@DisplayName("updateNode")
@HapiTestLifecycle
class AtomicNodeUpdateTest {

    private static List<X509Certificate> gossipCertificates;
    private static final String BATCH_OPERATOR = "batchOperator";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.doAdhoc(cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS));

        gossipCertificates = generateX509Certificates(2);
    }

    @HapiTest
    @DisplayName("cannot update a negative nodeid")
    final Stream<DynamicTest> cannotUpdateNegativeNodeId() {
        return hapiTest(
                atomicBatch(nodeUpdate("-1").hasPrecheck(INVALID_NODE_ID).batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasPrecheck(INVALID_NODE_ID));
    }

    @HapiTest
    @DisplayName("cannot update a missing nodeid")
    final Stream<DynamicTest> updateMissingNodeFail() {
        return hapiTest(
                atomicBatch(nodeUpdate("100").hasKnownStatus(INVALID_NODE_ID).batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    @DisplayName("cannot update a deleted node")
    final Stream<DynamicTest> updateDeletedNodeFail() throws CertificateEncodingException {
        final var nodeAccount = "nodeAccount";
        return hapiTest(
                newKeyNamed("adminKey"),
                cryptoCreate(nodeAccount),
                nodeCreate("testNode", nodeAccount)
                        .adminKey("adminKey")
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded()),
                nodeDelete("testNode"),
                atomicBatch(nodeUpdate("testNode")
                                .hasKnownStatus(INVALID_NODE_ID)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> validateAdminKey() throws CertificateEncodingException {
        final var nodeAccount = "nodeAccount";
        return hapiTest(
                newKeyNamed("adminKey"),
                cryptoCreate(nodeAccount),
                nodeCreate("testNode", nodeAccount)
                        .adminKey("adminKey")
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded()),
                atomicBatch(nodeUpdate("testNode")
                                .adminKey(NONSENSE_KEY)
                                .hasPrecheck(KEY_REQUIRED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasPrecheck(KEY_REQUIRED),
                atomicBatch(nodeUpdate("testNode")
                                .adminKey(WRONG_LENGTH_EDDSA_KEY)
                                .signedBy(GENESIS)
                                .hasPrecheck(INVALID_ADMIN_KEY)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasPrecheck(INVALID_ADMIN_KEY));
    }

    @HapiTest
    final Stream<DynamicTest> updateEmptyGossipCaCertificateFail() throws CertificateEncodingException {
        final var nodeAccount = "nodeAccount";
        return hapiTest(
                newKeyNamed("adminKey"),
                cryptoCreate(nodeAccount),
                nodeCreate("testNode", nodeAccount)
                        .adminKey("adminKey")
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded()),
                atomicBatch(nodeUpdate("testNode")
                                .gossipCaCertificate(new byte[0])
                                .hasPrecheck(INVALID_GOSSIP_CA_CERTIFICATE)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasPrecheck(INVALID_GOSSIP_CA_CERTIFICATE));
    }

    @LeakyHapiTest(overrides = {"nodes.updateAccountIdAllowed"})
    final Stream<DynamicTest> updateAccountIdNotAllowed() throws CertificateEncodingException {
        final var nodeAccount = "nodeAccount";
        return hapiTest(
                overriding("nodes.updateAccountIdAllowed", "false"),
                newKeyNamed("adminKey"),
                cryptoCreate(nodeAccount),
                nodeCreate("testNode", nodeAccount)
                        .adminKey("adminKey")
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded()),
                atomicBatch(nodeUpdate("testNode")
                                .accountId("0.0.100")
                                .hasKnownStatusFrom(UPDATE_NODE_ACCOUNT_NOT_ALLOWED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> validateGossipEndpoint() throws CertificateEncodingException {
        final var nodeAccount = "nodeAccount";
        return hapiTest(
                newKeyNamed("adminKey"),
                cryptoCreate(nodeAccount),
                nodeCreate("testNode", nodeAccount)
                        .adminKey("adminKey")
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded()),
                atomicBatch(nodeUpdate("testNode")
                                .adminKey("adminKey")
                                .gossipEndpoint(List.of(asServiceEndpoint("127.0.0.2:60"), invalidServiceEndpoint()))
                                .hasKnownStatus(INVALID_IPV4_ADDRESS)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(nodeUpdate("testNode")
                                .adminKey("adminKey")
                                .gossipEndpoint(
                                        List.of(asServiceEndpoint("127.0.0.3:60"), asDnsServiceEndpoint("test.dom:10")))
                                .hasKnownStatus(GOSSIP_ENDPOINT_CANNOT_HAVE_FQDN)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> validateServiceEndpoint() throws CertificateEncodingException {
        final var nodeAccount = "nodeAccount";
        return hapiTest(
                newKeyNamed("adminKey"),
                cryptoCreate(nodeAccount),
                nodeCreate("testNode", nodeAccount)
                        .adminKey("adminKey")
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded()),
                atomicBatch(nodeUpdate("testNode")
                                .adminKey("adminKey")
                                .serviceEndpoint(List.of(asServiceEndpoint("127.0.0.2:60"), invalidServiceEndpoint()))
                                .hasKnownStatus(INVALID_IPV4_ADDRESS)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> validateGrpcProxyEndpoint() throws CertificateEncodingException {
        final var nodeAccount = "nodeAccount";
        return hapiTest(
                newKeyNamed("adminKey"),
                cryptoCreate(nodeAccount),
                nodeCreate("testNode", nodeAccount)
                        .adminKey("adminKey")
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded()),
                atomicBatch(nodeUpdate("testNode")
                                .adminKey("adminKey")
                                .grpcProxyEndpoint(invalidServiceEndpoint())
                                .hasKnownStatus(INVALID_SERVICE_ENDPOINT)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> failsAtIngestForUnAuthorizedTxns() throws CertificateEncodingException {
        final String description = "His vorpal blade went snicker-snack!";
        final var nodeAccount = "nodeAccount";
        return hapiTest(
                newKeyNamed("adminKey"),
                cryptoCreate("payer").balance(10_000_000_000L),
                cryptoCreate(nodeAccount),
                nodeCreate("ntb", nodeAccount)
                        .adminKey("adminKey")
                        .description(description)
                        .fee(ONE_HBAR)
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded())
                        .via("nodeCreation"),
                atomicBatch(nodeUpdate("ntb")
                                .payingWith("payer")
                                .accountId("0.0.1000")
                                .hasKnownStatus(INVALID_SIGNATURE)
                                .fee(ONE_HBAR)
                                .via("updateNode")
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @LeakyHapiTest(overrides = {"nodes.maxServiceEndpoint"})
    final Stream<DynamicTest> validateServiceEndpointSize() throws CertificateEncodingException {
        final var nodeAccount = "nodeAccount";
        return hapiTest(
                overriding("nodes.maxServiceEndpoint", "2"),
                newKeyNamed("adminKey"),
                cryptoCreate(nodeAccount),
                nodeCreate("testNode", nodeAccount)
                        .adminKey("adminKey")
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded()),
                atomicBatch(nodeUpdate("testNode")
                                .adminKey("adminKey")
                                .serviceEndpoint(List.of(
                                        asServiceEndpoint("127.0.0.1:60"),
                                        asServiceEndpoint("127.0.0.2:60"),
                                        asServiceEndpoint("127.0.0.3:60")))
                                .hasKnownStatus(SERVICE_ENDPOINTS_EXCEEDED_LIMIT)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @LeakyHapiTest(overrides = {"nodes.maxGossipEndpoint"})
    final Stream<DynamicTest> validateGossipEndpointSize() throws CertificateEncodingException {
        final var nodeAccount = "nodeAccount";
        return hapiTest(
                overriding("nodes.maxGossipEndpoint", "2"),
                newKeyNamed("adminKey"),
                cryptoCreate(nodeAccount),
                nodeCreate("testNode", nodeAccount)
                        .adminKey("adminKey")
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded()),
                atomicBatch(nodeUpdate("testNode")
                                .adminKey("adminKey")
                                .gossipEndpoint(List.of(
                                        asServiceEndpoint("127.0.0.1:60"),
                                        asServiceEndpoint("127.0.0.2:60"),
                                        asServiceEndpoint("127.0.0.3:60")))
                                .hasKnownStatus(GOSSIP_ENDPOINTS_EXCEEDED_LIMIT)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @LeakyHapiTest(overrides = {"nodes.nodeMaxDescriptionUtf8Bytes"})
    final Stream<DynamicTest> updateTooLargeDescriptionFail() throws CertificateEncodingException {
        final var nodeAccount = "nodeAccount";
        return hapiTest(
                overriding("nodes.nodeMaxDescriptionUtf8Bytes", "3"),
                newKeyNamed("adminKey"),
                cryptoCreate(nodeAccount),
                nodeCreate("testNode", nodeAccount)
                        .adminKey("adminKey")
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded()),
                atomicBatch(nodeUpdate("testNode")
                                .adminKey("adminKey")
                                .description("toolarge")
                                .hasKnownStatus(INVALID_NODE_DESCRIPTION)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @LeakyHapiTest(overrides = {"nodes.enableDAB"})
    @DisplayName("DAB enable test")
    final Stream<DynamicTest> checkDABEnable() throws CertificateEncodingException {
        final var nodeAccount = "nodeAccount";
        return hapiTest(
                newKeyNamed("adminKey"),
                cryptoCreate(nodeAccount),
                nodeCreate("testNode", nodeAccount)
                        .adminKey("adminKey")
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded()),
                overriding("nodes.enableDAB", "false"),
                atomicBatch(nodeUpdate("testNode")
                                .adminKey("adminKey")
                                .serviceEndpoint(List.of(asServiceEndpoint("127.0.0.2:60"), invalidServiceEndpoint()))
                                .hasPrecheck(NOT_SUPPORTED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasPrecheck(NOT_SUPPORTED));
    }

    @HapiTest
    final Stream<DynamicTest> signedByCouncilNotAdminKeyFail() throws CertificateEncodingException {
        final var nodeAccount = "nodeAccount";
        return hapiTest(
                newKeyNamed("adminKey"),
                cryptoCreate(nodeAccount),
                nodeCreate("testNode", nodeAccount)
                        .adminKey("adminKey")
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded()),
                atomicBatch(nodeUpdate("testNode")
                                .signedBy(ADDRESS_BOOK_CONTROL)
                                .hasKnownStatus(INVALID_SIGNATURE)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> signedByAdminKeySuccess() throws CertificateEncodingException {
        final var nodeAccount = "nodeAccount";
        return hapiTest(
                newKeyNamed("adminKey"),
                cryptoCreate("payer").balance(10_000_000_000L),
                cryptoCreate(nodeAccount),
                nodeCreate("testNode", nodeAccount)
                        .adminKey("adminKey")
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded()),
                atomicBatch(nodeUpdate("testNode")
                                .payingWith("payer")
                                .signedBy("payer", "adminKey")
                                .description("updated description")
                                .via("successUpdate")
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getTxnRecord("successUpdate").logged());
    }

    @HapiTest
    final Stream<DynamicTest> webProxyAsIpAddressIsRejected() throws CertificateEncodingException {
        final var nodeAccount = "nodeAccount";
        return hapiTest(
                newKeyNamed("adminKey"),
                cryptoCreate(nodeAccount),
                nodeCreate("testNode", nodeAccount)
                        .adminKey("adminKey")
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded()),
                atomicBatch(nodeUpdate("testNode")
                                .signedByPayerAnd("adminKey")
                                .grpcProxyEndpoint(CommonPbjConverters.toPbj(GRPC_PROXY_ENDPOINT_IP))
                                .hasKnownStatus(INVALID_SERVICE_ENDPOINT)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }
}
