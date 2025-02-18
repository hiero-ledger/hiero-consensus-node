/*
 * Copyright (C) 2020-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hedera.services.bdd.suites.hip869;

import static com.hedera.node.app.hapi.utils.CommonPbjConverters.toPbj;
import static com.hedera.services.bdd.junit.EmbeddedReason.MUST_SKIP_INGEST;
import static com.hedera.services.bdd.junit.EmbeddedReason.NEEDS_STATE_ACCESS;
import static com.hedera.services.bdd.junit.hedera.utils.AddressBookUtils.endpointFor;
import static com.hedera.services.bdd.spec.HapiPropertySource.asEntityString;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.PropertySource.asAccount;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.WRONG_LENGTH_EDDSA_KEY;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeCreate;
import static com.hedera.services.bdd.spec.utilops.EmbeddedVerbs.viewNode;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsdWithin;
import static com.hedera.services.bdd.suites.HapiSuite.ADDRESS_BOOK_CONTROL;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.NONSENSE_KEY;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.SYSTEM_ADMIN;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.GOSSIP_ENDPOINTS_EXCEEDED_LIMIT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.GOSSIP_ENDPOINT_CANNOT_HAVE_FQDN;
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
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ServiceEndpoint;
import com.swirlds.platform.system.address.Address;
import com.swirlds.platform.test.fixtures.addressbook.RandomAddressBookBuilder;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.Spliterators;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;

@HapiTestLifecycle
public class NodeCreateTest {

    public static final String ED_25519_KEY = "ed25519Alias";
    public static List<ServiceEndpoint> GOSSIP_ENDPOINTS = Arrays.asList(
            ServiceEndpoint.newBuilder().setDomainName("test.com").setPort(123).build(),
            ServiceEndpoint.newBuilder().setDomainName("test2.com").setPort(123).build());
    public static List<ServiceEndpoint> SERVICES_ENDPOINTS = Arrays.asList(ServiceEndpoint.newBuilder()
            .setDomainName("service.com")
            .setPort(234)
            .build());
    public static List<ServiceEndpoint> GOSSIP_ENDPOINTS_IPS =
            Arrays.asList(endpointFor("192.168.1.200", 123), endpointFor("192.168.1.201", 123));
    public static List<ServiceEndpoint> SERVICES_ENDPOINTS_IPS = Arrays.asList(endpointFor("192.168.1.205", 234));
    private static List<X509Certificate> gossipCertificates;

    @BeforeAll
    static void beforeAll() {
        gossipCertificates = generateX509Certificates(2);
    }

    /**
     * This test is to check if the node creation fails during ingest when the admin key is missing.
     * @see <a href="https://github.com/hashgraph/hedera-improvement-proposal/blob/main/HIP/hip-869.md#specification">HIP-869</a>
     */
    @HapiTest
    final Stream<DynamicTest> adminKeyIsMissing() throws CertificateEncodingException {
        return hapiTest(nodeCreate("testNode")
                .adminKey(NONSENSE_KEY)
                .gossipCaCertificate(gossipCertificates.getFirst().getEncoded())
                .hasPrecheck(KEY_REQUIRED));
    }

    /**
     * This test is to check if the node creation fails during pureCheck when the admin key is missing.
     * @see <a href="https://github.com/hashgraph/hedera-improvement-proposal/blob/main/HIP/hip-869.md#specification">HIP-869</a>
     */
    @EmbeddedHapiTest(MUST_SKIP_INGEST)
    final Stream<DynamicTest> adminKeyIsMissingEmbedded()
            throws CertificateEncodingException { // skipping ingest but purecheck still throw the same

        return hapiTest(nodeCreate("nodeCreate")
                .setNode(asEntityString(4)) // exclude 1.2.3
                .adminKey(NONSENSE_KEY)
                .gossipCaCertificate(gossipCertificates.getFirst().getEncoded())
                .hasKnownStatus(KEY_REQUIRED));
    }

    /**
     * This test is to check if the node creation fails when admin key is invalid.
     * @see <a href="https://github.com/hashgraph/hedera-improvement-proposal/blob/main/HIP/hip-869.md#specification">HIP-869</a>
     */
    @HapiTest
    final Stream<DynamicTest> validateAdminKey() throws CertificateEncodingException {
        return hapiTest(nodeCreate("nodeCreate")
                .adminKey(WRONG_LENGTH_EDDSA_KEY)
                .signedBy(GENESIS)
                .gossipCaCertificate(gossipCertificates.getFirst().getEncoded())
                .hasPrecheck(INVALID_ADMIN_KEY));
    }

    /**
     * This test is to check if the node creation fails when the service endpoint is empty.
     * @see <a href="https://github.com/hashgraph/hedera-improvement-proposal/blob/main/HIP/hip-869.md#specification">HIP-869</a>
     */
    @HapiTest
    final Stream<DynamicTest> failOnInvalidServiceEndpoint() {

        return hapiTest(nodeCreate("nodeCreate").serviceEndpoint(List.of()).hasPrecheck(INVALID_SERVICE_ENDPOINT));
    }

    /**
     * This test is to check if the node creation fails when the gossip endpoint is empty.
     * @see <a href="https://github.com/hashgraph/hedera-improvement-proposal/blob/main/HIP/hip-869.md#specification">HIP-869</a>
     */
    @HapiTest
    final Stream<DynamicTest> failOnInvalidGossipEndpoint() {
        return hapiTest(
                newKeyNamed(ED_25519_KEY).shape(KeyShape.ED25519),
                nodeCreate("nodeCreate")
                        .adminKey(ED_25519_KEY)
                        .gossipEndpoint(List.of())
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
                nodeCreate("nodeCreate")
                        .adminKey(ED_25519_KEY)
                        .gossipCaCertificate(new byte[0])
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
                nodeCreate("nodeCreate")
                        .adminKey(ED_25519_KEY)
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded())
                        .gossipEndpoint(gossipEndpoints)
                        .hasKnownStatus(GOSSIP_ENDPOINTS_EXCEEDED_LIMIT));
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
                nodeCreate("nodeCreate")
                        .adminKey(ED_25519_KEY)
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded())
                        .serviceEndpoint(serviceEndpoints)
                        .hasKnownStatus(SERVICE_ENDPOINTS_EXCEEDED_LIMIT));
    }

    /**
     * Check that node creation succeeds with gossip and service endpoints using ips and all optional fields are recorded.
     * @see <a href="https://github.com/hashgraph/hedera-improvement-proposal/blob/main/HIP/hip-869.md#specification">HIP-869</a>
     */
    @EmbeddedHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> allFieldsSetHappyCaseForIps() throws CertificateEncodingException {
        return hapiTest(
                newKeyNamed(ED_25519_KEY).shape(KeyShape.ED25519),
                nodeCreate("nodeCreate")
                        .description("hello")
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded())
                        .grpcCertificateHash("hash".getBytes())
                        .accountId(asAccount(asEntityString(100)))
                        .gossipEndpoint(GOSSIP_ENDPOINTS_IPS)
                        .serviceEndpoint(SERVICES_ENDPOINTS_IPS)
                        .adminKey(ED_25519_KEY)
                        .hasPrecheck(OK)
                        .hasKnownStatus(SUCCESS),
                viewNode("nodeCreate", node -> {
                    assertEquals("hello", node.description(), "Description invalid");
                    try {
                        assertEquals(
                                ByteString.copyFrom(
                                        gossipCertificates.getFirst().getEncoded()),
                                ByteString.copyFrom(node.gossipCaCertificate().toByteArray()),
                                "Gossip CA invalid");
                    } catch (CertificateEncodingException e) {
                        throw new RuntimeException(e);
                    }

                    assertEquals(
                            ByteString.copyFrom("hash".getBytes()),
                            ByteString.copyFrom(node.grpcCertificateHash().toByteArray()),
                            "GRPC hash invalid");
                    assertEquals(100, node.accountId().accountNum(), "Account ID invalid");
                    assertEqualServiceEndpoints(GOSSIP_ENDPOINTS_IPS, node.gossipEndpoint());
                    assertEqualServiceEndpoints(SERVICES_ENDPOINTS_IPS, node.serviceEndpoint());
                    assertNotNull(node.adminKey(), "Admin key invalid");
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
        final var nodeCreate = nodeCreate("nodeCreate")
                .description("hello")
                .gossipCaCertificate(gossipCertificates.getFirst().getEncoded())
                .grpcCertificateHash("hash".getBytes())
                .accountId(asAccount(asEntityString(100)))
                .gossipEndpoint(GOSSIP_ENDPOINTS)
                .serviceEndpoint(SERVICES_ENDPOINTS)
                .adminKey(ED_25519_KEY)
                .hasPrecheck(OK)
                .hasKnownStatus(SUCCESS);
        return hapiTest(
                newKeyNamed(ED_25519_KEY).shape(KeyShape.ED25519),
                overriding("nodes.gossipFqdnRestricted", "false"),
                nodeCreate,
                viewNode("nodeCreate", node -> {
                    assertEquals("hello", node.description(), "Description invalid");
                    try {
                        assertEquals(
                                ByteString.copyFrom(
                                        gossipCertificates.getFirst().getEncoded()),
                                ByteString.copyFrom(node.gossipCaCertificate().toByteArray()),
                                "Gossip CA invalid");
                    } catch (CertificateEncodingException e) {
                        throw new RuntimeException(e);
                    }
                    assertEquals(
                            ByteString.copyFrom("hash".getBytes()),
                            ByteString.copyFrom(node.grpcCertificateHash().toByteArray()),
                            "GRPC hash invalid");
                    assertEquals(100, node.accountId().accountNum(), "Account ID invalid");
                    assertEqualServiceEndpoints(GOSSIP_ENDPOINTS, node.gossipEndpoint());
                    assertEqualServiceEndpoints(SERVICES_ENDPOINTS, node.serviceEndpoint());
                    assertEquals(toPbj(nodeCreate.getAdminKey()), node.adminKey(), "Admin key invalid");
                }));
    }

    /**
     * Check that node creation succeeds with minimum required fields set.
     * @see <a href="https://github.com/hashgraph/hedera-improvement-proposal/blob/main/HIP/hip-869.md#specification">HIP-869</a>
     */
    @EmbeddedHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> minimumFieldsSetHappyCase() throws CertificateEncodingException {
        final String description = "His vorpal blade went snicker-snack!";
        return hapiTest(
                nodeCreate("ntb")
                        .description(description)
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded()),
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
                nodeCreate("ntb")
                        .adminKey(ED_25519_KEY)
                        .payingWith("payer")
                        .signedBy("payer")
                        .setNode(asEntityString(4))
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded())
                        .hasKnownStatus(UNAUTHORIZED)
                        .via("nodeCreationFailed"),
                getTxnRecord("nodeCreationFailed").logged(),
                // Validate that the failed transaction charges the correct fees.
                validateChargedUsdWithin("nodeCreationFailed", 0.001, 3),
                nodeCreate("ntb")
                        .adminKey(ED_25519_KEY)
                        .fee(ONE_HBAR)
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded())
                        .via("nodeCreation"),
                getTxnRecord("nodeCreation").logged(),
                // But, note that the fee will not be charged for privileged payer
                // The fee is charged here because the payer is not privileged
                validateChargedUsdWithin("nodeCreation", 0.0, 0.0),

                // Submit with several signatures and the price should increase
                nodeCreate("ntb")
                        .adminKey(ED_25519_KEY)
                        .payingWith("payer")
                        .signedBy("payer", "randomAccount", "testKey")
                        .setNode(asEntityString(4))
                        .gossipCaCertificate(gossipCertificates.getLast().getEncoded())
                        .hasKnownStatus(UNAUTHORIZED)
                        .via("multipleSigsCreation"),
                validateChargedUsdWithin("multipleSigsCreation", 0.0011276316, 3.0));
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
                nodeCreate("ntb")
                        .adminKey(ED_25519_KEY)
                        .payingWith("payer")
                        .signedBy("payer")
                        .description(description)
                        .setNode(asEntityString(4))
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded())
                        .fee(1)
                        .hasKnownStatus(INSUFFICIENT_TX_FEE)
                        .via("nodeCreationFailed"),
                getTxnRecord("nodeCreationFailed").logged(),
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
                nodeCreate("ntb")
                        .adminKey(ED_25519_KEY)
                        .payingWith("payer")
                        .signedBy("payer", "randomAccount", "testKey")
                        .description(description)
                        .setNode(asEntityString(4))
                        .gossipCaCertificate(gossipCertificates.getLast().getEncoded())
                        .fee(1)
                        .hasKnownStatus(INSUFFICIENT_TX_FEE)
                        .via("multipleSigsCreation"));
    }

    @HapiTest
    final Stream<DynamicTest> failsAtIngestForUnAuthorizedTxns() throws CertificateEncodingException {
        final String description = "His vorpal blade went snicker-snack!";
        return hapiTest(
                cryptoCreate("payer").balance(ONE_HUNDRED_HBARS),
                nodeCreate("ntb")
                        .payingWith("payer")
                        .description(description)
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded())
                        .fee(ONE_HBAR)
                        .hasKnownStatus(UNAUTHORIZED)
                        .via("nodeCreation"));
    }

    @LeakyHapiTest(overrides = {"nodes.maxNumber"})
    @DisplayName("check error code MAX_NODES_CREATED is returned correctly")
    final Stream<DynamicTest> maxNodesReachedFail() throws CertificateEncodingException {
        return hapiTest(
                overriding("nodes.maxNumber", "1"),
                nodeCreate("testNode")
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded())
                        .hasKnownStatus(MAX_NODES_CREATED));
    }

    @HapiTest
    @DisplayName("Not existing account as accountId during nodeCreate failed")
    final Stream<DynamicTest> notExistingAccountFail() throws CertificateEncodingException {
        return hapiTest(nodeCreate("testNode")
                .accountId(AccountID.newBuilder().setAccountNum(50000).build())
                .gossipCaCertificate(gossipCertificates.getFirst().getEncoded())
                .hasKnownStatus(INVALID_NODE_ACCOUNT_ID));
    }

    @LeakyHapiTest(overrides = {"nodes.nodeMaxDescriptionUtf8Bytes"})
    @DisplayName("Check the max description size")
    final Stream<DynamicTest> updateTooLargeDescriptionFail() throws CertificateEncodingException {
        return hapiTest(
                overriding("nodes.nodeMaxDescriptionUtf8Bytes", "3"),
                nodeCreate("testNode")
                        .description("toolarge")
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded())
                        .hasKnownStatus(INVALID_NODE_DESCRIPTION));
    }

    @HapiTest
    @DisplayName("Check default setting, gossipEndpoint can not have domain names")
    final Stream<DynamicTest> gossipEndpointHaveDomainNameFail() throws CertificateEncodingException {
        return hapiTest(nodeCreate("testNode")
                .gossipEndpoint(GOSSIP_ENDPOINTS)
                .gossipCaCertificate(gossipCertificates.getFirst().getEncoded())
                .hasKnownStatus(GOSSIP_ENDPOINT_CANNOT_HAVE_FQDN));
    }

    @LeakyHapiTest(overrides = {"nodes.enableDAB"})
    @DisplayName("test DAB enable")
    final Stream<DynamicTest> checkDABEnable() throws CertificateEncodingException {
        return hapiTest(
                overriding("nodes.enableDAB", "false"),
                nodeCreate("testNode")
                        .description("toolarge")
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded())
                        .hasPrecheck(NOT_SUPPORTED));
    }

    @EmbeddedHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> createNodeWorkWithTreasuryPayer() throws CertificateEncodingException {
        return hapiTest(
                newKeyNamed("adminKey"),
                nodeCreate("testNode")
                        .adminKey("adminKey")
                        .payingWith(DEFAULT_PAYER)
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded())
                        .description("newNode"),
                viewNode("testNode", node -> assertEquals("newNode", node.description(), "Description invalid")));
    }

    @EmbeddedHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> createNodeWorkWithAddressBookAdminPayer() throws CertificateEncodingException {
        return hapiTest(
                newKeyNamed("adminKey"),
                nodeCreate("testNode")
                        .adminKey("adminKey")
                        .payingWith(ADDRESS_BOOK_CONTROL)
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded())
                        .description("newNode"),
                viewNode("testNode", node -> assertEquals("newNode", node.description(), "Description invalid")));
    }

    @EmbeddedHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> createNodeWorkWithSysAdminPayer() throws CertificateEncodingException {
        return hapiTest(
                newKeyNamed("adminKey"),
                nodeCreate("testNode")
                        .adminKey("adminKey")
                        .payingWith(SYSTEM_ADMIN)
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded())
                        .description("newNode"),
                viewNode("testNode", node -> assertEquals("newNode", node.description(), "Description invalid")));
    }

    @HapiTest
    final Stream<DynamicTest> createNodeFailsWithRegPayer() throws CertificateEncodingException {
        return hapiTest(
                cryptoCreate("payer").balance(ONE_HUNDRED_HBARS),
                newKeyNamed("adminKey"),
                nodeCreate("testNode")
                        .adminKey("adminKey")
                        .payingWith("payer")
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded())
                        .description("newNode")
                        .hasKnownStatus(UNAUTHORIZED));
    }

    private static void assertEqualServiceEndpoints(
            List<com.hederahashgraph.api.proto.java.ServiceEndpoint> expected,
            List<com.hedera.hapi.node.base.ServiceEndpoint> actual) {
        assertEquals(expected.size(), actual.size(), "Service endpoints size invalid");
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(
                    ByteString.copyFrom(expected.get(i).getIpAddressV4().toByteArray()),
                    ByteString.copyFrom(actual.get(i).ipAddressV4().toByteArray()),
                    "Service endpoint IP address invalid");
            assertEquals(
                    expected.get(i).getDomainName(),
                    actual.get(i).domainName(),
                    "Service endpoint domain name invalid");
            assertEquals(expected.get(i).getPort(), actual.get(i).port(), "Service endpoint port invalid");
        }
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
