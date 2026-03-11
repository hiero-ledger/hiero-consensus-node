// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip869.batch;

import static com.hedera.node.app.hapi.utils.CommonPbjConverters.toPbj;
import static com.hedera.services.bdd.junit.EmbeddedReason.MUST_SKIP_INGEST;
import static com.hedera.services.bdd.junit.EmbeddedReason.NEEDS_STATE_ACCESS;
import static com.hedera.services.bdd.junit.TestTags.ATOMIC_BATCH;
import static com.hedera.services.bdd.junit.TestTags.ONLY_EMBEDDED;
import static com.hedera.services.bdd.junit.hedera.embedded.EmbeddedMode.CONCURRENT;
import static com.hedera.services.bdd.junit.hedera.utils.NetworkUtils.endpointFor;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.keys.TrieSigMapGenerator.uniqueWithFullPrefixesFor;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeCreate;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.EmbeddedVerbs.viewNode;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsdWithin;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateInnerTxnChargedUsd;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ADDRESS_BOOK_CONTROL;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.NONSENSE_KEY;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.SYSTEM_ADMIN;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedFeeFromBytesFor;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.validateInnerTxnFees;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.NODE_CREATE_BASE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.SIGNATURE_FEE_AFTER_MULTIPLIER;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.KEY_REQUIRED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.UNAUTHORIZED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.EmbeddedHapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyEmbeddedHapiTest;
import com.hedera.services.bdd.junit.TargetEmbeddedMode;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.transactions.node.HapiNodeCreate;
import com.hedera.services.bdd.spec.utilops.embedded.ViewNodeOp;
import com.hedera.services.bdd.suites.hip869.NodeCreateTest;
import com.hederahashgraph.api.proto.java.ServiceEndpoint;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

// This test cases are direct copies of NodeCreateTest. The difference here is that
// we are wrapping the operations in an atomic batch to confirm that everything works as expected.
@Tag(ATOMIC_BATCH)
@Tag(ONLY_EMBEDDED)
@TargetEmbeddedMode(CONCURRENT)
@HapiTestLifecycle
class AtomicNodeCreateTestEmbedded {

    private static final List<ServiceEndpoint> GOSSIP_ENDPOINTS_IPS =
            Arrays.asList(endpointFor("192.168.1.200", 123), endpointFor("192.168.1.201", 123));
    private static final List<ServiceEndpoint> SERVICES_ENDPOINTS_IPS = List.of(endpointFor("192.168.1.205", 234));
    private static List<X509Certificate> gossipCertificates;
    private static final String BATCH_OPERATOR = "batchOperator";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.doAdhoc(cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS));

        gossipCertificates = NodeCreateTest.generateX509Certificates(2);
    }

    /**
     * This test is to check if the node creation fails during pureCheck when the admin key is missing.
     * @see <a href="https://github.com/hashgraph/hedera-improvement-proposal/blob/main/HIP/hip-869.md#specification">HIP-869</a>
     */
    @EmbeddedHapiTest(MUST_SKIP_INGEST)
    final Stream<DynamicTest> adminKeyIsMissingEmbedded()
            throws CertificateEncodingException { // skipping ingest but purecheck still throw the same
        final var nodeAccount = "nodeAccount";
        return hapiTest(
                cryptoCreate(nodeAccount),
                atomicBatch(nodeCreate("nodeCreate", nodeAccount)
                                .adminKey(NONSENSE_KEY)
                                .gossipCaCertificate(
                                        gossipCertificates.getFirst().getEncoded())
                                .hasKnownStatus(KEY_REQUIRED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasPrecheck(KEY_REQUIRED));
    }

    /**
     * Check that node creation succeeds with gossip and service endpoints using ips and all optional fields are recorded.
     * @see <a href="https://github.com/hashgraph/hedera-improvement-proposal/blob/main/HIP/hip-869.md#specification">HIP-869</a>
     */
    @EmbeddedHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> allFieldsSetHappyCaseForIps() throws CertificateEncodingException {
        final var nodeAccount = "nodeAccount";
        final var nodeCreate = AtomicNodeCreateTest.canonicalNodeCreate(
                        nodeAccount, gossipCertificates.getFirst().getEncoded())
                .gossipEndpoint(GOSSIP_ENDPOINTS_IPS)
                .serviceEndpoint(SERVICES_ENDPOINTS_IPS)
                // The web proxy endpoint can never be an IP address
                .grpcWebProxyEndpoint(AtomicNodeCreateTest.GRPC_PROXY_ENDPOINT_FQDN);

        return hapiTest(
                newKeyNamed(AtomicNodeCreateTest.ED_25519_KEY).shape(KeyShape.ED25519),
                cryptoCreate(nodeAccount),
                atomicBatch(nodeCreate.batchKey(BATCH_OPERATOR)).payingWith(BATCH_OPERATOR),
                verifyCanonicalCreate(nodeCreate),
                viewNode("nodeCreate", node -> {
                    assertEqualServiceEndpoints(GOSSIP_ENDPOINTS_IPS, node.gossipEndpoint());
                    assertEqualServiceEndpoints(SERVICES_ENDPOINTS_IPS, node.serviceEndpoint());
                    assertEqualServiceEndpoint(AtomicNodeCreateTest.GRPC_PROXY_ENDPOINT_FQDN, node.grpcProxyEndpoint());
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
        final var nodeAccount = "nodeAccount";
        final var nodeCreate = AtomicNodeCreateTest.canonicalNodeCreate(
                nodeAccount, gossipCertificates.getFirst().getEncoded());

        return hapiTest(
                overriding("nodes.gossipFqdnRestricted", "false"),
                newKeyNamed(AtomicNodeCreateTest.ED_25519_KEY).shape(KeyShape.ED25519),
                cryptoCreate(nodeAccount),
                atomicBatch(nodeCreate.batchKey(BATCH_OPERATOR)).payingWith(BATCH_OPERATOR),
                verifyCanonicalCreate(nodeCreate),
                viewNode("nodeCreate", node -> {
                    assertEqualServiceEndpoints(AtomicNodeCreateTest.GOSSIP_ENDPOINTS_FQDNS, node.gossipEndpoint());
                    assertEqualServiceEndpoints(AtomicNodeCreateTest.SERVICES_ENDPOINTS_FQDNS, node.serviceEndpoint());
                    assertEqualServiceEndpoint(AtomicNodeCreateTest.GRPC_PROXY_ENDPOINT_FQDN, node.grpcProxyEndpoint());
                }));
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
        final var nodeAccount = "nodeAccount";
        return hapiTest(
                cryptoCreate(nodeAccount),
                atomicBatch(nodeCreate("ntb", nodeAccount)
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
        final var nodeAccount = "nodeAccount";
        return hapiTest(
                newKeyNamed(AtomicNodeCreateTest.ED_25519_KEY).shape(KeyShape.ED25519),
                newKeyNamed("testKey"),
                newKeyNamed("randomAccount"),
                cryptoCreate("payer").balance(10_000_000_000L),
                cryptoCreate(nodeAccount),
                // Submit to a different node so ingest check is skipped
                atomicBatch(nodeCreate("ntb", nodeAccount)
                                .adminKey(AtomicNodeCreateTest.ED_25519_KEY)
                                .payingWith("payer")
                                .signedBy("payer")
                                .sigMapPrefixes(uniqueWithFullPrefixesFor("payer"))
                                .gossipCaCertificate(
                                        gossipCertificates.getFirst().getEncoded())
                                .hasKnownStatus(UNAUTHORIZED)
                                .via("nodeCreationFailed")
                                .batchKey(BATCH_OPERATOR))
                        .via("atomic")
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                // Validate that the failed transaction charges the correct fees.
                withOpContext((spec, log) -> allRunFor(
                        spec,
                        validateInnerTxnFees(
                                "nodeCreationFailed",
                                "atomic",
                                0.001,
                                NODE_CREATE_BASE_FEE_USD + expectedFeeFromBytesFor(spec, log, "nodeCreationFailed"),
                                3))),
                atomicBatch(nodeCreate("ntb", nodeAccount)
                                .adminKey(AtomicNodeCreateTest.ED_25519_KEY)
                                .fee(ONE_HBAR)
                                .gossipCaCertificate(
                                        gossipCertificates.getFirst().getEncoded())
                                .via("nodeCreation")
                                .batchKey(BATCH_OPERATOR))
                        .via("atomic")
                        .payingWith(BATCH_OPERATOR),
                // But, note that the fee will not be charged for privileged payer
                // The fee is charged here because the payer is not privileged
                validateInnerTxnChargedUsd("nodeCreation", "atomic", 0.0, 0.0),

                // Submit with several signatures and the price should increase
                atomicBatch(nodeCreate("ntb", nodeAccount)
                                .adminKey(AtomicNodeCreateTest.ED_25519_KEY)
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
                withOpContext((spec, log) -> allRunFor(
                        spec,
                        validateInnerTxnFees(
                                "multipleSigsCreation",
                                "atomic",
                                0.0011276316,
                                NODE_CREATE_BASE_FEE_USD
                                        + 2 * SIGNATURE_FEE_AFTER_MULTIPLIER
                                        + expectedFeeFromBytesFor(spec, log, "multipleSigsCreation"),
                                3))));
    }

    /**
     * Check that node creation fails during ingest when the transaction is unauthorized.
     * @see <a href="https://github.com/hashgraph/hedera-improvement-proposal/blob/main/HIP/hip-869.md#specification">HIP-869</a>
     */
    @EmbeddedHapiTest(MUST_SKIP_INGEST)
    final Stream<DynamicTest> validateFeesInsufficientAmount() throws CertificateEncodingException {
        final var nodeAccount = "nodeAccount";
        final String description = "His vorpal blade went snicker-snack!";
        return hapiTest(
                newKeyNamed(AtomicNodeCreateTest.ED_25519_KEY).shape(KeyShape.ED25519),
                newKeyNamed("testKey"),
                newKeyNamed("randomAccount"),
                cryptoCreate("payer").balance(10_000_000_000L),
                cryptoCreate(nodeAccount),
                // Submit to a different node so ingest check is skipped
                atomicBatch(nodeCreate("ntb", nodeAccount)
                                .adminKey(AtomicNodeCreateTest.ED_25519_KEY)
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
                nodeCreate("ntb", nodeAccount)
                        .adminKey(AtomicNodeCreateTest.ED_25519_KEY)
                        .description(description)
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded())
                        .via("nodeCreation"),
                getTxnRecord("nodeCreation").logged(),
                // But, note that the fee will not be charged for privileged payer
                // The fee is charged here because the payer is not privileged
                validateChargedUsdWithin("nodeCreation", 0.0, 0.0),

                // Submit with several signatures and the price should increase
                atomicBatch(nodeCreate("ntb", nodeAccount)
                                .adminKey(AtomicNodeCreateTest.ED_25519_KEY)
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

    @EmbeddedHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> createNodeWorkWithTreasuryPayer() throws CertificateEncodingException {
        final var nodeAccount = "nodeAccount";
        return hapiTest(
                newKeyNamed("adminKey"),
                cryptoCreate(nodeAccount),
                atomicBatch(nodeCreate("testNode", nodeAccount)
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
        final var nodeAccount = "nodeAccount";
        return hapiTest(
                newKeyNamed("adminKey"),
                cryptoCreate(nodeAccount),
                atomicBatch(nodeCreate("testNode", nodeAccount)
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
        final var nodeAccount = "nodeAccount";
        return hapiTest(
                newKeyNamed("adminKey"),
                cryptoCreate(nodeAccount),
                atomicBatch(nodeCreate("testNode", nodeAccount)
                                .adminKey("adminKey")
                                .payingWith(SYSTEM_ADMIN)
                                .gossipCaCertificate(
                                        gossipCertificates.getFirst().getEncoded())
                                .description("newNode")
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                viewNode("testNode", node -> assertEquals("newNode", node.description(), "Description invalid")));
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
}
