// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip869.batch;

import static com.hedera.node.app.hapi.utils.CommonPbjConverters.toPbj;
import static com.hedera.services.bdd.junit.EmbeddedReason.MUST_SKIP_INGEST;
import static com.hedera.services.bdd.junit.EmbeddedReason.NEEDS_STATE_ACCESS;
import static com.hedera.services.bdd.junit.hedera.utils.AddressBookUtils.endpointFor;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.keys.TrieSigMapGenerator.uniqueWithFullPrefixesFor;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.WRONG_LENGTH_EDDSA_KEY;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeCreate;
import static com.hedera.services.bdd.spec.utilops.EmbeddedVerbs.viewNode;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingTwo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsdWithin;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateInnerTxnChargedUsd;
import static com.hedera.services.bdd.suites.HapiSuite.ADDRESS_BOOK_CONTROL;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.NONSENSE_KEY;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.SYSTEM_ADMIN;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.GOSSIP_ENDPOINTS_EXCEEDED_LIMIT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.GOSSIP_ENDPOINT_CANNOT_HAVE_FQDN;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.GRPC_WEB_PROXY_NOT_SUPPORTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.EmbeddedHapiTest;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyEmbeddedHapiTest;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.transactions.node.HapiNodeCreate;
import com.hedera.services.bdd.spec.utilops.embedded.ViewNodeOp;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ServiceEndpoint;
import com.swirlds.platform.test.fixtures.addressbook.RandomAddressBookBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Spliterators;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.hiero.consensus.model.roster.Address;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;

// This test cases are direct copies of NodeCreateTest. The difference here is that
// we are wrapping the operations in an atomic batch to confirm that everything works as expected.
@HapiTestLifecycle
public class AtomicNodeCreateTest {

    public static final String ED_25519_KEY = "ed25519Alias";
    public static List<ServiceEndpoint> GOSSIP_ENDPOINTS_FQDNS = Arrays.asList(
            ServiceEndpoint.newBuilder().setDomainName("test.com").setPort(123).build(),
            ServiceEndpoint.newBuilder().setDomainName("test2.com").setPort(123).build());
    public static List<ServiceEndpoint> SERVICES_ENDPOINTS_FQDNS = List.of(ServiceEndpoint.newBuilder()
            .setDomainName("service.com")
            .setPort(234)
            .build());
    public static final ServiceEndpoint GRPC_PROXY_ENDPOINT_FQDN = endpointFor("grpc.web.proxy.com", 123);
    public static List<ServiceEndpoint> GOSSIP_ENDPOINTS_IPS =
            Arrays.asList(endpointFor("192.168.1.200", 123), endpointFor("192.168.1.201", 123));
    public static List<ServiceEndpoint> SERVICES_ENDPOINTS_IPS = List.of(endpointFor("192.168.1.205", 234));
    public static final ServiceEndpoint GRPC_PROXY_ENDPOINT_IP = endpointFor("192.168.1.255", 123);
    private static List<X509Certificate> gossipCertificates;
    private static final String BATCH_OPERATOR = "batchOperator";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(
                Map.of("atomicBatch.isEnabled", "true", "atomicBatch.maxNumberOfTransactions", "50"));
        testLifecycle.doAdhoc(cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS));

        gossipCertificates = generateX509Certificates(2);
    }

    /**
     * This test is to check if the node creation fails during ingest when the admin key is missing.
     * @see <a href="https://github.com/hashgraph/hedera-improvement-proposal/blob/main/HIP/hip-869.md#specification">HIP-869</a>
     */
    @HapiTest
    final Stream<DynamicTest> adminKeyIsMissing() throws CertificateEncodingException {
        return hapiTest(atomicBatch(nodeCreate("testNode")
                        .adminKey(NONSENSE_KEY)
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded())
                        .hasPrecheck(KEY_REQUIRED)
                        .batchKey(BATCH_OPERATOR))
                .payingWith(BATCH_OPERATOR)
                .hasPrecheck(KEY_REQUIRED));
    }

    /**
     * This test is to check if the node creation fails during pureCheck when the admin key is missing.
     * @see <a href="https://github.com/hashgraph/hedera-improvement-proposal/blob/main/HIP/hip-869.md#specification">HIP-869</a>
     */
    @EmbeddedHapiTest(MUST_SKIP_INGEST)
    final Stream<DynamicTest> adminKeyIsMissingEmbedded()
            throws CertificateEncodingException { // skipping ingest but purecheck still throw the same

        return hapiTest(atomicBatch(nodeCreate("nodeCreate")
                        .adminKey(NONSENSE_KEY)
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded())
                        .hasKnownStatus(KEY_REQUIRED)
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
        return hapiTest(atomicBatch(nodeCreate("nodeCreate")
                        .adminKey(WRONG_LENGTH_EDDSA_KEY)
                        .signedBy(GENESIS)
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded())
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
        return hapiTest(atomicBatch(nodeCreate("nodeCreate")
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
        return hapiTest(
                newKeyNamed(ED_25519_KEY).shape(KeyShape.ED25519),
                atomicBatch(nodeCreate("nodeCreate")
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
        return hapiTest(
                newKeyNamed(ED_25519_KEY).shape(KeyShape.ED25519),
                atomicBatch(nodeCreate("nodeCreate")
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
        return hapiTest(
                newKeyNamed(ED_25519_KEY).shape(KeyShape.ED25519),
                atomicBatch(nodeCreate("nodeCreate")
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
        return hapiTest(
                newKeyNamed(ED_25519_KEY).shape(KeyShape.ED25519),
                atomicBatch(nodeCreate("nodeCreate")
                                .adminKey(ED_25519_KEY)
                                .gossipCaCertificate(
                                        gossipCertificates.getFirst().getEncoded())
                                .serviceEndpoint(serviceEndpoints)
                                .hasKnownStatus(SERVICE_ENDPOINTS_EXCEEDED_LIMIT)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    /**
     * Check that node creation succeeds with gossip and service endpoints using ips and all optional fields are recorded.
     * @see <a href="https://github.com/hashgraph/hedera-improvement-proposal/blob/main/HIP/hip-869.md#specification">HIP-869</a>
     */
    @EmbeddedHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> allFieldsSetHappyCaseForIps() throws CertificateEncodingException {
        final var nodeCreate = canonicalNodeCreate()
                .gossipEndpoint(GOSSIP_ENDPOINTS_IPS)
                .serviceEndpoint(SERVICES_ENDPOINTS_IPS)
                // The web proxy endpoint can never be an IP address
                .grpcWebProxyEndpoint(GRPC_PROXY_ENDPOINT_FQDN);
        return hapiTest(
                newKeyNamed(ED_25519_KEY).shape(KeyShape.ED25519),
                atomicBatch(nodeCreate.batchKey(BATCH_OPERATOR)).payingWith(BATCH_OPERATOR),
                verifyCanonicalCreate(nodeCreate),
                viewNode("nodeCreate", node -> {
                    assertEqualServiceEndpoints(GOSSIP_ENDPOINTS_IPS, node.gossipEndpoint());
                    assertEqualServiceEndpoints(SERVICES_ENDPOINTS_IPS, node.serviceEndpoint());
                    assertEqualServiceEndpoint(GRPC_PROXY_ENDPOINT_FQDN, node.grpcProxyEndpoint());
                }));
    }

    /**
     * Check that node creation succeeds with gossip and service endpoints using domain names and all optional fields are recorded.
     * @see <a href="https://github.com/hashgraph/hedera-improvement-proposal/blob/main/HIP/hip-869.md#specification">HIP-869</a>
     */
    @LeakyEmbeddedHapiTest(
            reason = NEEDS_STATE_ACCESS,
            overrides = {"nodes.gossipFqdnRestricted"})
    final Stream<DynamicTest> allFieldsSetHappyCaseForDomains() throws CertificateEncodingException {
        final var nodeCreate = canonicalNodeCreate();
        return hapiTest(
                overriding("nodes.gossipFqdnRestricted", "false"),
                newKeyNamed(ED_25519_KEY).shape(KeyShape.ED25519),
                atomicBatch(nodeCreate.batchKey(BATCH_OPERATOR)).payingWith(BATCH_OPERATOR),
                verifyCanonicalCreate(nodeCreate),
                viewNode("nodeCreate", node -> {
                    assertEqualServiceEndpoints(GOSSIP_ENDPOINTS_FQDNS, node.gossipEndpoint());
                    assertEqualServiceEndpoints(SERVICES_ENDPOINTS_FQDNS, node.serviceEndpoint());
                    assertEqualServiceEndpoint(GRPC_PROXY_ENDPOINT_FQDN, node.grpcProxyEndpoint());
                }));
    }

    @LeakyHapiTest(overrides = {"nodes.gossipFqdnRestricted", "nodes.webProxyEndpointsEnabled"})
    final Stream<DynamicTest> webProxySetWhenNotEnabledReturnsNotSupported() throws CertificateEncodingException {
        final var nodeCreate = canonicalNodeCreate();
        return hapiTest(
                overridingTwo("nodes.gossipFqdnRestricted", "false", "nodes.webProxyEndpointsEnabled", "false"),
                newKeyNamed(ED_25519_KEY).shape(KeyShape.ED25519),
                atomicBatch(nodeCreate
                                .hasKnownStatus(GRPC_WEB_PROXY_NOT_SUPPORTED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> webProxyAsIpAddressIsRejected() throws CertificateEncodingException {
        return hapiTest(
                newKeyNamed("adminKey"),
                atomicBatch(nodeCreate("nodeCreate")
                                .adminKey("adminKey")
                                .gossipCaCertificate(
                                        gossipCertificates.getFirst().getEncoded())
                                .grpcWebProxyEndpoint(GRPC_PROXY_ENDPOINT_IP)
                                .hasKnownStatus(INVALID_SERVICE_ENDPOINT)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    private static HapiNodeCreate canonicalNodeCreate() throws CertificateEncodingException {
        return nodeCreate("nodeCreate")
                .description("hello")
                .gossipCaCertificate(gossipCertificates.getFirst().getEncoded())
                .grpcCertificateHash("hash".getBytes())
                .accountNum(100)
                // Defaults to FQDN's for all endpoints
                .gossipEndpoint(GOSSIP_ENDPOINTS_FQDNS)
                .serviceEndpoint(SERVICES_ENDPOINTS_FQDNS)
                .grpcWebProxyEndpoint(GRPC_PROXY_ENDPOINT_FQDN)
                .adminKey(ED_25519_KEY)
                .hasPrecheck(OK)
                .hasKnownStatus(SUCCESS);
    }

    private static ViewNodeOp verifyCanonicalCreate(final HapiNodeCreate nodeCreate) {
        return viewNode("nodeCreate", node -> {
            assertEquals("hello", node.description(), "Description invalid");
            try {
                assertEquals(
                        ByteString.copyFrom(gossipCertificates.getFirst().getEncoded()),
                        ByteString.copyFrom(node.gossipCaCertificate().toByteArray()),
                        "Gossip CA invalid");
            } catch (CertificateEncodingException e) {
                throw new RuntimeException(e);
            }
            assertEquals(
                    ByteString.copyFrom("hash".getBytes()),
                    ByteString.copyFrom(node.grpcCertificateHash().toByteArray()),
                    "GRPC hash invalid");
            assertNotNull(node.accountId(), "Account ID invalid");
            assertEquals(100, node.accountId().accountNum(), "Account ID invalid");
            assertNotNull(nodeCreate.getAdminKey(), " Admin key invalid");
            assertEquals(toPbj(nodeCreate.getAdminKey()), node.adminKey(), "Admin key invalid");
        });
    }

    /**
     * Check that node creation succeeds with minimum required fields set.
     * @see <a href="https://github.com/hashgraph/hedera-improvement-proposal/blob/main/HIP/hip-869.md#specification">HIP-869</a>
     */
    @EmbeddedHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> minimumFieldsSetHappyCase() throws CertificateEncodingException {
        final String description = "His vorpal blade went snicker-snack!";
        return hapiTest(
                atomicBatch(nodeCreate("ntb")
                                .description(description)
                                .gossipCaCertificate(
                                        gossipCertificates.getFirst().getEncoded())
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                viewNode(
                        "ntb", node -> assertEquals(description, node.description(), "Node was created successfully")));
    }

    /**
     * Check that appropriate fees are charged during node creation.
     */
    @EmbeddedHapiTest(MUST_SKIP_INGEST)
    final Stream<DynamicTest> validateFees() throws CertificateEncodingException {
        return hapiTest(
                newKeyNamed(ED_25519_KEY).shape(KeyShape.ED25519),
                newKeyNamed("testKey"),
                newKeyNamed("randomAccount"),
                cryptoCreate("payer").balance(10_000_000_000L),
                // Submit to a different node so ingest check is skipped
                atomicBatch(nodeCreate("ntb")
                                .adminKey(ED_25519_KEY)
                                .payingWith("payer")
                                .signedBy("payer")
                                .gossipCaCertificate(
                                        gossipCertificates.getFirst().getEncoded())
                                .hasKnownStatus(UNAUTHORIZED)
                                .via("nodeCreationFailed")
                                .batchKey(BATCH_OPERATOR))
                        .via("atomic")
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                getTxnRecord("nodeCreationFailed").logged(),
                // Validate that the failed transaction charges the correct fees.
                validateInnerTxnChargedUsd("nodeCreationFailed", "atomic", 0.001, 3),
                atomicBatch(nodeCreate("ntb")
                                .adminKey(ED_25519_KEY)
                                .fee(ONE_HBAR)
                                .gossipCaCertificate(
                                        gossipCertificates.getFirst().getEncoded())
                                .via("nodeCreation")
                                .batchKey(BATCH_OPERATOR))
                        .via("atomic")
                        .payingWith(BATCH_OPERATOR),
                getTxnRecord("nodeCreation").logged(),
                // But, note that the fee will not be charged for privileged payer
                // The fee is charged here because the payer is not privileged
                validateInnerTxnChargedUsd("nodeCreation", "atomic", 0.0, 0.0),

                // Submit with several signatures and the price should increase
                atomicBatch(nodeCreate("ntb")
                                .adminKey(ED_25519_KEY)
                                .payingWith("payer")
                                .signedBy("payer", "randomAccount", "testKey")
                                .sigMapPrefixes(uniqueWithFullPrefixesFor("payer", "randomAccount", "testKey"))
                                .gossipCaCertificate(
                                        gossipCertificates.getLast().getEncoded())
                                .hasKnownStatus(UNAUTHORIZED)
                                .via("multipleSigsCreation")
                                .batchKey(BATCH_OPERATOR))
                        .via("atomic")
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                validateInnerTxnChargedUsd("multipleSigsCreation", "atomic", 0.0011276316, 3.0));
    }

    /**
     * Check that node creation fails during ingest when the transaction is unauthorized.
     * @see <a href="https://github.com/hashgraph/hedera-improvement-proposal/blob/main/HIP/hip-869.md#specification">HIP-869</a>
     */
    @EmbeddedHapiTest(MUST_SKIP_INGEST)
    final Stream<DynamicTest> validateFeesInsufficientAmount() throws CertificateEncodingException {
        final String description = "His vorpal blade went snicker-snack!";
        return hapiTest(
                newKeyNamed(ED_25519_KEY).shape(KeyShape.ED25519),
                newKeyNamed("testKey"),
                newKeyNamed("randomAccount"),
                cryptoCreate("payer").balance(10_000_000_000L),
                // Submit to a different node so ingest check is skipped
                atomicBatch(nodeCreate("ntb")
                                .adminKey(ED_25519_KEY)
                                .payingWith("payer")
                                .signedBy("payer")
                                .description(description)
                                .gossipCaCertificate(
                                        gossipCertificates.getFirst().getEncoded())
                                .fee(1)
                                .hasKnownStatus(INSUFFICIENT_TX_FEE)
                                .batchKey(BATCH_OPERATOR))
                        .via("nodeCreationFailed")
                        .hasPrecheck(INSUFFICIENT_TX_FEE)
                        .payingWith(BATCH_OPERATOR),
                nodeCreate("ntb")
                        .adminKey(ED_25519_KEY)
                        .description(description)
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded())
                        .via("nodeCreation"),
                getTxnRecord("nodeCreation").logged(),
                // But, note that the fee will not be charged for privileged payer
                // The fee is charged here because the payer is not privileged
                validateChargedUsdWithin("nodeCreation", 0.0, 0.0),

                // Submit with several signatures and the price should increase
                atomicBatch(nodeCreate("ntb")
                                .adminKey(ED_25519_KEY)
                                .payingWith("payer")
                                .signedBy("payer", "randomAccount", "testKey")
                                .description(description)
                                .gossipCaCertificate(
                                        gossipCertificates.getLast().getEncoded())
                                .fee(1)
                                .hasKnownStatus(INSUFFICIENT_TX_FEE)
                                .via("multipleSigsCreation")
                                .batchKey(BATCH_OPERATOR))
                        .hasPrecheck(INSUFFICIENT_TX_FEE)
                        .payingWith(BATCH_OPERATOR));
    }

    @HapiTest
    final Stream<DynamicTest> failsAtIngestForUnAuthorizedTxns() throws CertificateEncodingException {
        final String description = "His vorpal blade went snicker-snack!";
        return hapiTest(
                cryptoCreate("payer").balance(ONE_HUNDRED_HBARS),
                atomicBatch(nodeCreate("ntb")
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
        return hapiTest(
                overriding("nodes.maxNumber", "1"),
                atomicBatch(nodeCreate("testNode")
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
        return hapiTest(atomicBatch(nodeCreate("testNode")
                        .accountId(AccountID.newBuilder().setAccountNum(50000).build())
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded())
                        .hasKnownStatus(INVALID_NODE_ACCOUNT_ID)
                        .batchKey(BATCH_OPERATOR))
                .payingWith(BATCH_OPERATOR)
                .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @LeakyHapiTest(overrides = {"nodes.nodeMaxDescriptionUtf8Bytes"})
    @DisplayName("Check the max description size")
    final Stream<DynamicTest> updateTooLargeDescriptionFail() throws CertificateEncodingException {
        return hapiTest(
                overriding("nodes.nodeMaxDescriptionUtf8Bytes", "3"),
                atomicBatch(nodeCreate("testNode")
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
        return hapiTest(atomicBatch(nodeCreate("testNode")
                        .gossipEndpoint(GOSSIP_ENDPOINTS_FQDNS)
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded())
                        .hasKnownStatus(GOSSIP_ENDPOINT_CANNOT_HAVE_FQDN)
                        .batchKey(BATCH_OPERATOR))
                .payingWith(BATCH_OPERATOR)
                .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @LeakyHapiTest(overrides = {"nodes.enableDAB"})
    @DisplayName("test DAB enable")
    final Stream<DynamicTest> checkDABEnable() throws CertificateEncodingException {
        return hapiTest(
                overriding("nodes.enableDAB", "false"),
                atomicBatch(nodeCreate("testNode")
                                .description("toolarge")
                                .gossipCaCertificate(
                                        gossipCertificates.getFirst().getEncoded())
                                .hasPrecheck(NOT_SUPPORTED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasPrecheck(NOT_SUPPORTED));
    }

    @EmbeddedHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> createNodeWorkWithTreasuryPayer() throws CertificateEncodingException {
        return hapiTest(
                newKeyNamed("adminKey"),
                atomicBatch(nodeCreate("testNode")
                                .adminKey("adminKey")
                                .payingWith(DEFAULT_PAYER)
                                .gossipCaCertificate(
                                        gossipCertificates.getFirst().getEncoded())
                                .description("newNode")
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                viewNode("testNode", node -> assertEquals("newNode", node.description(), "Description invalid")));
    }

    @EmbeddedHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> createNodeWorkWithAddressBookAdminPayer() throws CertificateEncodingException {
        return hapiTest(
                newKeyNamed("adminKey"),
                atomicBatch(nodeCreate("testNode")
                                .adminKey("adminKey")
                                .payingWith(ADDRESS_BOOK_CONTROL)
                                .gossipCaCertificate(
                                        gossipCertificates.getFirst().getEncoded())
                                .description("newNode")
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                viewNode("testNode", node -> assertEquals("newNode", node.description(), "Description invalid")));
    }

    @EmbeddedHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> createNodeWorkWithSysAdminPayer() throws CertificateEncodingException {
        return hapiTest(
                newKeyNamed("adminKey"),
                atomicBatch(nodeCreate("testNode")
                                .adminKey("adminKey")
                                .payingWith(SYSTEM_ADMIN)
                                .gossipCaCertificate(
                                        gossipCertificates.getFirst().getEncoded())
                                .description("newNode")
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                viewNode("testNode", node -> assertEquals("newNode", node.description(), "Description invalid")));
    }

    @HapiTest
    final Stream<DynamicTest> createNodeFailsWithRegPayer() throws CertificateEncodingException {
        return hapiTest(
                cryptoCreate("payer").balance(ONE_HUNDRED_HBARS),
                newKeyNamed("adminKey"),
                atomicBatch(nodeCreate("testNode")
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
        return hapiTest(
                newKeyNamed("adminKey"),
                atomicBatch(nodeCreate("testNode")
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

    private static void assertEqualServiceEndpoints(
            List<ServiceEndpoint> expected, List<com.hedera.hapi.node.base.ServiceEndpoint> actual) {
        assertEquals(
                expected.size(),
                actual.size(),
                "Service endpoints sizes don't match: expected " + expected.size() + " but got " + actual.size());
        for (int i = 0; i < expected.size(); i++) {
            assertEqualServiceEndpoint(expected.get(i), actual.get(i));
        }
    }

    private static void assertEqualServiceEndpoint(
            ServiceEndpoint expected, com.hedera.hapi.node.base.ServiceEndpoint actual) {
        if (expected == null && actual == null) {
            return;
        }
        if (actual == null) {
            throw new AssertionError("Service endpoint is null when non-null was expected");
        }

        assertEquals(
                ByteString.copyFrom(expected.getIpAddressV4().toByteArray()),
                ByteString.copyFrom(actual.ipAddressV4().toByteArray()),
                "Service endpoint IP address invalid");
        assertEquals(expected.getDomainName(), actual.domainName(), "Service endpoint domain name invalid");
        assertEquals(expected.getPort(), actual.port(), "Service endpoint port invalid");
    }

    public static List<X509Certificate> generateX509Certificates(final int n) {
        final var randomAddressBook = RandomAddressBookBuilder.create(new Random())
                .withSize(n)
                .withRealKeysEnabled(true)
                .build();
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(randomAddressBook.iterator(), 0), false)
                .map(Address::getSigCert)
                .collect(Collectors.toList());
    }
}
