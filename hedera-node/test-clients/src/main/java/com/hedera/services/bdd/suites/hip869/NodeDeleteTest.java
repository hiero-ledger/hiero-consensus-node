// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip869;

import static com.hedera.services.bdd.junit.EmbeddedReason.MUST_SKIP_INGEST;
import static com.hedera.services.bdd.junit.EmbeddedReason.NEEDS_STATE_ACCESS;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.keys.TrieSigMapGenerator.uniqueWithFullPrefixesFor;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeDelete;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.EmbeddedVerbs.viewNode;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsdWithin;
import static com.hedera.services.bdd.suites.HapiSuite.ADDRESS_BOOK_CONTROL;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.SYSTEM_ADMIN;
import static com.hedera.services.bdd.suites.hip869.NodeCreateTest.generateX509Certificates;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_NODE_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.NODE_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.NOT_SUPPORTED;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.services.bdd.junit.EmbeddedHapiTest;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;

@HapiTestLifecycle
public class NodeDeleteTest {
    private static List<X509Certificate> gossipCertificates;

    @BeforeAll
    static void beforeAll() {
        gossipCertificates = generateX509Certificates(1);
    }

    @EmbeddedHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> deleteNodeWorks() throws CertificateEncodingException {
        final String nodeName = "mytestnode";

        return hapiTest(
                nodeCreate(nodeName)
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded()),
                viewNode(nodeName, node -> assertFalse(node.deleted(), "Node should not be deleted")),
                nodeDelete(nodeName),
                viewNode(nodeName, node -> assertTrue(node.deleted(), "Node should be deleted")));
    }

    @EmbeddedHapiTest(MUST_SKIP_INGEST)
    final Stream<DynamicTest> validateFees() throws CertificateEncodingException {
        final String description = "His vorpal blade went snicker-snack!";
        return hapiTest(
                newKeyNamed("testKey"),
                newKeyNamed("randomAccount"),
                cryptoCreate("payer").balance(10_000_000_000L),
                nodeCreate("node100")
                        .description(description)
                        .fee(ONE_HBAR)
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded()),
                // Submit to a different node so ingest check is skipped
                nodeDelete("node100")
                        .setNode(5)
                        .payingWith("payer")
                        .hasKnownStatus(INVALID_SIGNATURE)
                        .via("failedDeletion"),
                getTxnRecord("failedDeletion").logged(),
                // The fee is charged here because the payer is not privileged
                validateChargedUsdWithin("failedDeletion", 0.001, 3.0),

                // Submit with several signatures and the price should increase
                nodeDelete("node100")
                        .setNode(5)
                        .payingWith("payer")
                        .signedBy("payer", "randomAccount", "testKey")
                        .sigMapPrefixes(uniqueWithFullPrefixesFor("payer", "randomAccount", "testKey"))
                        .hasKnownStatus(INVALID_SIGNATURE)
                        .via("multipleSigsDeletion"),
                validateChargedUsdWithin("multipleSigsDeletion", 0.0011276316, 3.0),
                nodeDelete("node100").via("deleteNode"),
                getTxnRecord("deleteNode").logged(),
                // The fee is not charged here because the payer is privileged
                validateChargedUsdWithin("deleteNode", 0.0, 3.0));
    }

    @EmbeddedHapiTest(MUST_SKIP_INGEST)
    final Stream<DynamicTest> validateFeesInsufficientAmount() throws CertificateEncodingException {
        final String description = "His vorpal blade went snicker-snack!";
        return hapiTest(
                newKeyNamed("testKey"),
                newKeyNamed("randomAccount"),
                cryptoCreate("payer").balance(10_000_000_000L),
                nodeCreate("node100")
                        .description(description)
                        .fee(ONE_HBAR)
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded()),
                // Submit to a different node so ingest check is skipped
                nodeDelete("node100")
                        .setNode(5)
                        .fee(1)
                        .payingWith("payer")
                        .hasKnownStatus(INSUFFICIENT_TX_FEE)
                        .via("failedDeletion"),
                getTxnRecord("failedDeletion").logged(),
                // Submit with several signatures and the price should increase
                nodeDelete("node100")
                        .setNode(5)
                        .fee(ONE_HBAR)
                        .payingWith("payer")
                        .signedBy("payer", "randomAccount", "testKey")
                        .hasKnownStatus(INVALID_SIGNATURE)
                        .via("multipleSigsDeletion"),
                nodeDelete("node100").via("deleteNode"),
                getTxnRecord("deleteNode").logged());
    }

    @HapiTest
    final Stream<DynamicTest> failsAtIngestForUnAuthorizedTxns() throws CertificateEncodingException {
        final String description = "His vorpal blade went snicker-snack!";
        return hapiTest(
                cryptoCreate("payer").balance(10_000_000_000L),
                nodeCreate("ntb")
                        .description(description)
                        .fee(ONE_HBAR)
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded()),
                nodeDelete("ntb")
                        .payingWith("payer")
                        .fee(ONE_HBAR)
                        .hasKnownStatus(INVALID_SIGNATURE)
                        .via("failedDeletion"));
    }

    @HapiTest
    final Stream<DynamicTest> handleNodeNotExist() {
        final String nodeName = "33";
        return hapiTest(nodeDelete(nodeName).hasKnownStatus(INVALID_NODE_ID));
    }

    @HapiTest
    final Stream<DynamicTest> handleNodeAlreadyDeleted() throws CertificateEncodingException {
        final String nodeName = "mytestnode";
        return hapiTest(
                nodeCreate(nodeName)
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded()),
                nodeDelete(nodeName),
                nodeDelete(nodeName).signedBy(GENESIS).hasKnownStatus(NODE_DELETED));
    }

    @HapiTest
    final Stream<DynamicTest> handleCanBeExecutedJustWithPrivilegedAccount() throws CertificateEncodingException {
        long PAYER_BALANCE = 1_999_999_999L;
        final String nodeName = "mytestnode";

        return hapiTest(
                newKeyNamed("adminKey"),
                newKeyNamed("wrongKey"),
                cryptoCreate("payer").balance(PAYER_BALANCE).key("wrongKey"),
                nodeCreate(nodeName)
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded()),
                nodeDelete(nodeName)
                        .payingWith("payer")
                        .signedBy("payer", "wrongKey")
                        .hasKnownStatus(INVALID_SIGNATURE),
                nodeDelete(nodeName));
    }

    @LeakyHapiTest(overrides = {"nodes.enableDAB"})
    @DisplayName("DAB enable test")
    final Stream<DynamicTest> checkDABEnable() throws CertificateEncodingException {
        final String nodeName = "mytestnode";

        return hapiTest(
                nodeCreate(nodeName)
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded()),
                overriding("nodes.enableDAB", "false"),
                nodeDelete(nodeName).hasPrecheck(NOT_SUPPORTED));
    }

    @HapiTest
    final Stream<DynamicTest> signWithWrongAdminKeyFailed() throws CertificateEncodingException {
        return hapiTest(
                newKeyNamed("payerKey"),
                cryptoCreate("payer").key("payerKey").balance(10_000_000_000L),
                newKeyNamed("adminKey"),
                nodeCreate("testNode")
                        .adminKey("adminKey")
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded()),
                nodeDelete("testNode").payingWith("payer").signedBy("payerKey").hasPrecheck(INVALID_SIGNATURE));
    }

    @EmbeddedHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> signWithCorrectAdminKeySuccess() throws CertificateEncodingException {
        return hapiTest(
                newKeyNamed("payerKey"),
                cryptoCreate("payer").key("payerKey").balance(10_000_000_000L),
                newKeyNamed("adminKey"),
                nodeCreate("testNode")
                        .adminKey("adminKey")
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded()),
                nodeDelete("testNode").payingWith("payer").signedBy("payer", "adminKey"),
                viewNode("testNode", node -> assertTrue(node.deleted(), "Node should be deleted")));
    }

    @EmbeddedHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> deleteNodeWorkWithValidAdminKey() throws CertificateEncodingException {
        return hapiTest(
                newKeyNamed("adminKey"),
                nodeCreate("testNode")
                        .adminKey("adminKey")
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded()),
                viewNode("testNode", node -> assertFalse(node.deleted(), "Node should not be deleted")),
                nodeDelete("testNode").signedBy(DEFAULT_PAYER, "adminKey"),
                viewNode("testNode", node -> assertTrue(node.deleted(), "Node should be deleted")));
    }

    @EmbeddedHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> deleteNodeWorkWithTreasuryPayer() throws CertificateEncodingException {
        return hapiTest(
                newKeyNamed("adminKey"),
                nodeCreate("testNode")
                        .adminKey("adminKey")
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded()),
                viewNode("testNode", node -> assertFalse(node.deleted(), "Node should not be deleted")),
                nodeDelete("testNode").payingWith(DEFAULT_PAYER),
                viewNode("testNode", node -> assertTrue(node.deleted(), "Node should be deleted")));
    }

    @EmbeddedHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> deleteNodeWorkWithAddressBookAdminPayer() throws CertificateEncodingException {
        return hapiTest(
                newKeyNamed("adminKey"),
                cryptoTransfer(tinyBarsFromTo(GENESIS, ADDRESS_BOOK_CONTROL, ONE_HUNDRED_HBARS))
                        .fee(ONE_HBAR),
                nodeCreate("testNode")
                        .adminKey("adminKey")
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded()),
                viewNode("testNode", node -> assertFalse(node.deleted(), "Node should not be deleted")),
                nodeDelete("testNode").payingWith(ADDRESS_BOOK_CONTROL),
                viewNode("testNode", node -> assertTrue(node.deleted(), "Node should be deleted")));
    }

    @EmbeddedHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> deleteNodeWorkWithSysAdminPayer() throws CertificateEncodingException {
        return hapiTest(
                newKeyNamed("adminKey"),
                nodeCreate("testNode")
                        .adminKey("adminKey")
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded()),
                viewNode("testNode", node -> assertFalse(node.deleted(), "Node should not be deleted")),
                nodeDelete("testNode").payingWith(SYSTEM_ADMIN),
                viewNode("testNode", node -> assertTrue(node.deleted(), "Node should be deleted")));
    }
}
