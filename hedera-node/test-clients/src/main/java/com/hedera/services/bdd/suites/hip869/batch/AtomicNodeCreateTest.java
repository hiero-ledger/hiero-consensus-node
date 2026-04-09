// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip869.batch;

import static com.hedera.services.bdd.junit.TestTags.ATOMIC_BATCH;
import static com.hedera.services.bdd.junit.hedera.utils.NetworkUtils.endpointFor;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.WRONG_LENGTH_EDDSA_KEY;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeUpdate;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingTwo;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.NONSENSE_KEY;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.GOSSIP_ENDPOINTS_EXCEEDED_LIMIT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.GOSSIP_ENDPOINT_CANNOT_HAVE_FQDN;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.GRPC_WEB_PROXY_NOT_SUPPORTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ADMIN_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_GOSSIP_CA_CERTIFICATE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_GOSSIP_ENDPOINT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_NODE_ACCOUNT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_NODE_DESCRIPTION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SERVICE_ENDPOINT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.KEY_REQUIRED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MAX_NODES_CREATED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.NOT_SUPPORTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SERVICE_ENDPOINTS_EXCEEDED_LIMIT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.UNAUTHORIZED;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.transactions.node.HapiNodeCreate;
import com.hedera.services.bdd.suites.hip869.NodeCreateTest;
import com.hederahashgraph.api.proto.java.ServiceEndpoint;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

// This test cases are direct copies of NodeCreateTest. The difference here is that
// we are wrapping the operations in an atomic batch to confirm that everything works as expected.
@Tag(ATOMIC_BATCH)
@HapiTestLifecycle
class AtomicNodeCreateTest {

    public static final String ED_25519_KEY = "ed25519Alias";
    public static List<ServiceEndpoint> GOSSIP_ENDPOINTS_FQDNS = Arrays.asList(
            ServiceEndpoint.newBuilder().setDomainName("test.com").setPort(123).build(),
            ServiceEndpoint.newBuilder().setDomainName("test2.com").setPort(123).build());
    public static List<ServiceEndpoint> SERVICES_ENDPOINTS_FQDNS = List.of(ServiceEndpoint.newBuilder()
            .setDomainName("service.com")
            .setPort(234)
            .build());
    public static final ServiceEndpoint GRPC_PROXY_ENDPOINT_FQDN = endpointFor("grpc.web.proxy.com", 123);
    private static final ServiceEndpoint GRPC_PROXY_ENDPOINT_IP = endpointFor("192.168.1.255", 123);
    private static List<X509Certificate> gossipCertificates;
    private static final String BATCH_OPERATOR = "batchOperator";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.doAdhoc(cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS));

        gossipCertificates = NodeCreateTest.generateX509Certificates(2);
    }

    /**
     * This test is to check if the node creation fails during ingest when the admin key is missing.
     * @see <a href="https://github.com/hashgraph/hedera-improvement-proposal/blob/main/HIP/hip-869.md#specification">HIP-869</a>
     */
    @HapiTest
    final Stream<DynamicTest> adminKeyIsMissing() throws CertificateEncodingException {
        final var nodeAccount = "nodeAccount";
        return hapiTest(
                cryptoCreate(nodeAccount),
                atomicBatch(nodeCreate("testNode", nodeAccount)
                                .adminKey(NONSENSE_KEY)
                                .gossipCaCertificate(
                                        gossipCertificates.getFirst().getEncoded())
                                .hasPrecheck(KEY_REQUIRED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasPrecheck(KEY_REQUIRED));
    }

    /**
     * This test is to check if the node creation fails when admin key is invalid.
     * @see <a href="https://github.com/hashgraph/hedera-improvement-proposal/blob/main/HIP/hip-869.md#specification">HIP-869</a>
     */
    @HapiTest
    final Stream<DynamicTest> validateAdminKey() throws CertificateEncodingException {
        final var nodeAccount = "nodeAccount";
        return hapiTest(
                cryptoCreate(nodeAccount),
                atomicBatch(nodeCreate("nodeCreate", nodeAccount)
                                .adminKey(WRONG_LENGTH_EDDSA_KEY)
                                .signedBy(GENESIS)
                                .gossipCaCertificate(
                                        gossipCertificates.getFirst().getEncoded())
                                .hasPrecheck(INVALID_ADMIN_KEY)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasPrecheck(INVALID_ADMIN_KEY));
    }

    /**
     * This test is to check if the node creation fails when the service endpoint is empty.
     * @see <a href="https://github.com/hashgraph/hedera-improvement-proposal/blob/main/HIP/hip-869.md#specification">HIP-869</a>
     */
    @HapiTest
    final Stream<DynamicTest> failOnInvalidServiceEndpoint() {
        final var nodeAccount = "nodeAccount";
        return hapiTest(
                cryptoCreate(nodeAccount),
                atomicBatch(nodeCreate("nodeCreate", nodeAccount)
                                .serviceEndpoint(List.of())
                                .hasPrecheck(INVALID_SERVICE_ENDPOINT)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasPrecheck(INVALID_SERVICE_ENDPOINT));
    }

    /**
     * This test is to check if the node creation fails when the gossip endpoint is empty.
     * @see <a href="https://github.com/hashgraph/hedera-improvement-proposal/blob/main/HIP/hip-869.md#specification">HIP-869</a>
     */
    @HapiTest
    final Stream<DynamicTest> failOnInvalidGossipEndpoint() {
        final var nodeAccount = "nodeAccount";
        return hapiTest(
                newKeyNamed(ED_25519_KEY).shape(KeyShape.ED25519),
                cryptoCreate(nodeAccount),
                atomicBatch(nodeCreate("nodeCreate", nodeAccount)
                                .adminKey(ED_25519_KEY)
                                .gossipEndpoint(List.of())
                                .hasPrecheck(INVALID_GOSSIP_ENDPOINT)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasPrecheck(INVALID_GOSSIP_ENDPOINT));
    }

    /**
     * This test is to check if the node creation fails when the gossip CA certificate is invalid.
     * @see <a href="https://github.com/hashgraph/hedera-improvement-proposal/blob/main/HIP/hip-869.md#specification">HIP-869</a>
     */
    @HapiTest
    final Stream<DynamicTest> failOnEmptyGossipCaCertificate() {
        final var nodeAccount = "nodeAccount";
        return hapiTest(
                newKeyNamed(ED_25519_KEY).shape(KeyShape.ED25519),
                cryptoCreate(nodeAccount),
                atomicBatch(nodeCreate("nodeCreate", nodeAccount)
                                .adminKey(ED_25519_KEY)
                                .gossipCaCertificate(new byte[0])
                                .hasPrecheck(INVALID_GOSSIP_CA_CERTIFICATE)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasPrecheck(INVALID_GOSSIP_CA_CERTIFICATE));
    }

    /**
     * Check that node creation fails when more than 10 domain names are provided for gossip endpoints.
     * @see <a href="https://github.com/hashgraph/hedera-improvement-proposal/blob/main/HIP/hip-869.md#specification">HIP-869</a>
     */
    @HapiTest
    final Stream<DynamicTest> failOnTooManyGossipEndpoints() throws CertificateEncodingException {
        final List<ServiceEndpoint> gossipEndpoints = Arrays.asList(
                ServiceEndpoint.newBuilder()
                        .setDomainName("test.com")
                        .setPort(123)
                        .build(),
                ServiceEndpoint.newBuilder()
                        .setDomainName("test2.com")
                        .setPort(123)
                        .build(),
                ServiceEndpoint.newBuilder()
                        .setDomainName("test3.com")
                        .setPort(123)
                        .build(),
                ServiceEndpoint.newBuilder()
                        .setDomainName("test4.com")
                        .setPort(123)
                        .build(),
                ServiceEndpoint.newBuilder()
                        .setDomainName("test5.com")
                        .setPort(123)
                        .build(),
                ServiceEndpoint.newBuilder()
                        .setDomainName("test6.com")
                        .setPort(123)
                        .build(),
                ServiceEndpoint.newBuilder()
                        .setDomainName("test7.com")
                        .setPort(123)
                        .build(),
                ServiceEndpoint.newBuilder()
                        .setDomainName("test8.com")
                        .setPort(123)
                        .build(),
                ServiceEndpoint.newBuilder()
                        .setDomainName("test9.com")
                        .setPort(123)
                        .build(),
                ServiceEndpoint.newBuilder()
                        .setDomainName("test10.com")
                        .setPort(123)
                        .build(),
                ServiceEndpoint.newBuilder()
                        .setDomainName("test11.com")
                        .setPort(123)
                        .build());
        final var nodeAccount = "nodeAccount";
        return hapiTest(
                newKeyNamed(ED_25519_KEY).shape(KeyShape.ED25519),
                cryptoCreate(nodeAccount),
                atomicBatch(nodeCreate("nodeCreate", nodeAccount)
                                .adminKey(ED_25519_KEY)
                                .gossipCaCertificate(
                                        gossipCertificates.getFirst().getEncoded())
                                .gossipEndpoint(gossipEndpoints)
                                .hasKnownStatus(GOSSIP_ENDPOINTS_EXCEEDED_LIMIT)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    /**
     * Check that node creation fails when more than 8 domain names are provided for service endpoints.
     * @see <a href="https://github.com/hashgraph/hedera-improvement-proposal/blob/main/HIP/hip-869.md#specification">HIP-869</a>
     */
    @HapiTest
    final Stream<DynamicTest> failOnTooManyServiceEndpoints() throws CertificateEncodingException {
        final List<ServiceEndpoint> serviceEndpoints = Arrays.asList(
                ServiceEndpoint.newBuilder()
                        .setDomainName("test.com")
                        .setPort(123)
                        .build(),
                ServiceEndpoint.newBuilder()
                        .setDomainName("test2.com")
                        .setPort(123)
                        .build(),
                ServiceEndpoint.newBuilder()
                        .setDomainName("test3.com")
                        .setPort(123)
                        .build(),
                ServiceEndpoint.newBuilder()
                        .setDomainName("test4.com")
                        .setPort(123)
                        .build(),
                ServiceEndpoint.newBuilder()
                        .setDomainName("test5.com")
                        .setPort(123)
                        .build(),
                ServiceEndpoint.newBuilder()
                        .setDomainName("test6.com")
                        .setPort(123)
                        .build(),
                ServiceEndpoint.newBuilder()
                        .setDomainName("test7.com")
                        .setPort(123)
                        .build(),
                ServiceEndpoint.newBuilder()
                        .setDomainName("test8.com")
                        .setPort(123)
                        .build(),
                ServiceEndpoint.newBuilder()
                        .setDomainName("test9.com")
                        .setPort(123)
                        .build());
        final var nodeAccount = "nodeAccount";
        return hapiTest(
                newKeyNamed(ED_25519_KEY).shape(KeyShape.ED25519),
                cryptoCreate(nodeAccount),
                atomicBatch(nodeCreate("nodeCreate", nodeAccount)
                                .adminKey(ED_25519_KEY)
                                .gossipCaCertificate(
                                        gossipCertificates.getFirst().getEncoded())
                                .serviceEndpoint(serviceEndpoints)
                                .hasKnownStatus(SERVICE_ENDPOINTS_EXCEEDED_LIMIT)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @LeakyHapiTest(overrides = {"nodes.gossipFqdnRestricted", "nodes.webProxyEndpointsEnabled"})
    final Stream<DynamicTest> webProxySetWhenNotEnabledReturnsNotSupported() throws CertificateEncodingException {
        final var nodeAccount = "nodeAccount";
        final var nodeCreate =
                canonicalNodeCreate(nodeAccount, gossipCertificates.getFirst().getEncoded());

        return hapiTest(
                overridingTwo("nodes.gossipFqdnRestricted", "false", "nodes.webProxyEndpointsEnabled", "false"),
                newKeyNamed(ED_25519_KEY).shape(KeyShape.ED25519),
                cryptoCreate(nodeAccount),
                atomicBatch(nodeCreate
                                .hasKnownStatus(GRPC_WEB_PROXY_NOT_SUPPORTED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> webProxyAsIpAddressIsRejected() throws CertificateEncodingException {
        final var nodeAccount = "nodeAccount";
        return hapiTest(
                newKeyNamed("adminKey"),
                cryptoCreate(nodeAccount),
                atomicBatch(nodeCreate("nodeCreate", nodeAccount)
                                .adminKey("adminKey")
                                .gossipCaCertificate(
                                        gossipCertificates.getFirst().getEncoded())
                                .grpcWebProxyEndpoint(GRPC_PROXY_ENDPOINT_IP)
                                .hasKnownStatus(INVALID_SERVICE_ENDPOINT)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    protected static HapiNodeCreate canonicalNodeCreate(final String nodeAccount, final byte[] gossipCert) {
        return nodeCreate("nodeCreate", nodeAccount)
                .description("hello")
                .gossipCaCertificate(gossipCert)
                .grpcCertificateHash("hash".getBytes())
                // Defaults to FQDN's for all endpoints
                .gossipEndpoint(GOSSIP_ENDPOINTS_FQDNS)
                .serviceEndpoint(SERVICES_ENDPOINTS_FQDNS)
                .grpcWebProxyEndpoint(GRPC_PROXY_ENDPOINT_FQDN)
                .adminKey(ED_25519_KEY)
                .hasPrecheck(OK)
                .hasKnownStatus(SUCCESS);
    }

    @HapiTest
    final Stream<DynamicTest> failsAtIngestForUnAuthorizedTxns() throws CertificateEncodingException {
        final String description = "His vorpal blade went snicker-snack!";
        final var nodeAccount = "nodeAccount";
        return hapiTest(
                cryptoCreate("payer").balance(ONE_HUNDRED_HBARS),
                cryptoCreate(nodeAccount),
                atomicBatch(nodeCreate("ntb", nodeAccount)
                                .payingWith("payer")
                                .description(description)
                                .gossipCaCertificate(
                                        gossipCertificates.getFirst().getEncoded())
                                .fee(ONE_HBAR)
                                .hasKnownStatus(UNAUTHORIZED)
                                .via("nodeCreation")
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @LeakyHapiTest(overrides = {"nodes.maxNumber"})
    @DisplayName("check error code MAX_NODES_CREATED is returned correctly")
    final Stream<DynamicTest> maxNodesReachedFail() throws CertificateEncodingException {
        final var nodeAccount = "nodeAccount";
        return hapiTest(
                overriding("nodes.maxNumber", "1"),
                cryptoCreate(nodeAccount),
                atomicBatch(nodeCreate("testNode", nodeAccount)
                                .gossipCaCertificate(
                                        gossipCertificates.getFirst().getEncoded())
                                .hasKnownStatus(MAX_NODES_CREATED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    @DisplayName("Not existing account as accountId during nodeCreate failed")
    final Stream<DynamicTest> notExistingAccountFail() throws CertificateEncodingException {
        final var nodeAccount = "nodeAccount";
        return hapiTest(
                cryptoCreate(nodeAccount),
                atomicBatch(nodeCreate("testNode", nodeAccount)
                                .accountNum(50000)
                                .gossipCaCertificate(
                                        gossipCertificates.getFirst().getEncoded())
                                .hasKnownStatus(INVALID_NODE_ACCOUNT_ID)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @LeakyHapiTest(overrides = {"nodes.nodeMaxDescriptionUtf8Bytes"})
    @DisplayName("Check the max description size")
    final Stream<DynamicTest> updateTooLargeDescriptionFail() throws CertificateEncodingException {
        final var nodeAccount = "nodeAccount";
        return hapiTest(
                overriding("nodes.nodeMaxDescriptionUtf8Bytes", "3"),
                cryptoCreate(nodeAccount),
                atomicBatch(nodeCreate("testNode", nodeAccount)
                                .description("toolarge")
                                .gossipCaCertificate(
                                        gossipCertificates.getFirst().getEncoded())
                                .hasKnownStatus(INVALID_NODE_DESCRIPTION)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    @DisplayName("Check default setting, gossipEndpoint can not have domain names")
    final Stream<DynamicTest> gossipEndpointHaveDomainNameFail() throws CertificateEncodingException {
        final var nodeAccount = "nodeAccount";
        return hapiTest(
                cryptoCreate(nodeAccount),
                atomicBatch(nodeCreate("testNode", nodeAccount)
                                .gossipEndpoint(GOSSIP_ENDPOINTS_FQDNS)
                                .gossipCaCertificate(
                                        gossipCertificates.getFirst().getEncoded())
                                .hasKnownStatus(GOSSIP_ENDPOINT_CANNOT_HAVE_FQDN)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @LeakyHapiTest(overrides = {"nodes.enableDAB"})
    @DisplayName("test DAB enable")
    final Stream<DynamicTest> checkDABEnable() throws CertificateEncodingException {
        final var nodeAccount = "nodeAccount";
        return hapiTest(
                overriding("nodes.enableDAB", "false"),
                cryptoCreate(nodeAccount),
                atomicBatch(nodeCreate("testNode", nodeAccount)
                                .description("toolarge")
                                .gossipCaCertificate(
                                        gossipCertificates.getFirst().getEncoded())
                                .hasPrecheck(NOT_SUPPORTED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasPrecheck(NOT_SUPPORTED));
    }

    @HapiTest
    final Stream<DynamicTest> createNodeFailsWithRegPayer() throws CertificateEncodingException {
        final var nodeAccount = "nodeAccount";
        return hapiTest(
                cryptoCreate("payer").balance(ONE_HUNDRED_HBARS),
                newKeyNamed("adminKey"),
                cryptoCreate(nodeAccount),
                atomicBatch(nodeCreate("testNode", nodeAccount)
                                .adminKey("adminKey")
                                .payingWith("payer")
                                .gossipCaCertificate(
                                        gossipCertificates.getFirst().getEncoded())
                                .description("newNode")
                                .hasKnownStatus(UNAUTHORIZED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> createNodeWithDefaultGrpcProxyFails() throws CertificateEncodingException {
        final var nodeAccount = "nodeAccount";
        return hapiTest(
                newKeyNamed("adminKey"),
                cryptoCreate(nodeAccount),
                atomicBatch(nodeCreate("testNode", nodeAccount)
                                .adminKey("adminKey")
                                .gossipCaCertificate(
                                        gossipCertificates.getFirst().getEncoded())
                                .grpcWebProxyEndpoint(ServiceEndpoint.getDefaultInstance())
                                .description("newNode")
                                .hasKnownStatus(INVALID_SERVICE_ENDPOINT)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    @DisplayName("atomic batch with node update + node create assigns correct receipt IDs")
    final Stream<DynamicTest> atomicBatchUpdateAndCreateReceiptIds() throws CertificateEncodingException {
        final var nodeAccount = "nodeAccount";
        final var newNodeAccount = "newNodeAccount";
        return hapiTest(
                newKeyNamed("adminKey"),
                cryptoCreate(nodeAccount),
                cryptoCreate(newNodeAccount),
                // Pre-create a consensus node
                nodeCreate("existingNode", nodeAccount)
                        .adminKey("adminKey")
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded()),
                // Atomic batch: update existing + create new
                atomicBatch(
                                nodeUpdate("existingNode")
                                        .description("updated")
                                        .signedBy(DEFAULT_PAYER, "adminKey")
                                        .batchKey(BATCH_OPERATOR),
                                nodeCreate("newNode", newNodeAccount)
                                        .adminKey("adminKey")
                                        .gossipCaCertificate(
                                                gossipCertificates.getLast().getEncoded())
                                        .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(SUCCESS));
    }
}
