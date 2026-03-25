// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip869.batch;

import static com.hedera.services.bdd.junit.EmbeddedReason.MUST_SKIP_INGEST;
import static com.hedera.services.bdd.junit.EmbeddedReason.NEEDS_STATE_ACCESS;
import static com.hedera.services.bdd.junit.TestTags.ATOMIC_BATCH;
import static com.hedera.services.bdd.junit.TestTags.ONLY_EMBEDDED;
import static com.hedera.services.bdd.junit.hedera.embedded.EmbeddedMode.CONCURRENT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.keys.TrieSigMapGenerator.uniqueWithFullPrefixesFor;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeDelete;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.EmbeddedVerbs.viewNode;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.safeValidateInnerTxnChargedUsd;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateInnerTxnChargedUsd;
import static com.hedera.services.bdd.suites.HapiSuite.ADDRESS_BOOK_CONTROL;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.SYSTEM_ADMIN;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.NODE_DELETE_BASE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.SIGNATURE_FEE_AFTER_MULTIPLIER;
import static com.hedera.services.bdd.suites.hip869.NodeCreateTest.generateX509Certificates;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.services.bdd.junit.EmbeddedHapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.TargetEmbeddedMode;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

// This test cases are direct copies of NodeDeleteTest. The difference here is that
// we are wrapping the operations in an atomic batch to confirm that everything works as expected.
@Tag(ATOMIC_BATCH)
@Tag(ONLY_EMBEDDED)
@TargetEmbeddedMode(CONCURRENT)
@HapiTestLifecycle
class AtomicNodeDeleteTestEmbedded {

    private static List<X509Certificate> gossipCertificates;

    private static final String BATCH_OPERATOR = "batchOperator";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.doAdhoc(cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS));

        gossipCertificates = generateX509Certificates(1);
    }

    @EmbeddedHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> deleteNodeWorks() throws CertificateEncodingException {
        final String nodeName = "mytestnode";
        final String nodeAccount = "nodeAccount";
        return hapiTest(
                cryptoCreate(nodeAccount),
                nodeCreate(nodeName, nodeAccount)
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded()),
                viewNode(nodeName, node -> assertFalse(node.deleted(), "Node should not be deleted")),
                atomicBatch(nodeDelete(nodeName).batchKey(BATCH_OPERATOR)).payingWith(BATCH_OPERATOR),
                viewNode(nodeName, node -> assertTrue(node.deleted(), "Node should be deleted")));
    }

    @EmbeddedHapiTest(MUST_SKIP_INGEST)
    final Stream<DynamicTest> validateFees() throws CertificateEncodingException {
        final String description = "His vorpal blade went snicker-snack!";
        final var nodeAccount = "nodeAccount";
        return hapiTest(
                newKeyNamed("testKey"),
                newKeyNamed("randomAccount"),
                cryptoCreate("payer").balance(10_000_000_000L),
                cryptoCreate(nodeAccount),
                nodeCreate("node100", nodeAccount)
                        .description(description)
                        .fee(ONE_HBAR)
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded()),
                // Submit to a different node so ingest check is skipped
                atomicBatch(nodeDelete("node100")
                                .payingWith("payer")
                                .hasKnownStatus(INVALID_SIGNATURE)
                                .via("failedDeletion")
                                .batchKey(BATCH_OPERATOR))
                        .via("atomic")
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                getTxnRecord("failedDeletion").logged(),
                // The fee is charged here because the payer is not privileged
                safeValidateInnerTxnChargedUsd("failedDeletion", "atomic", 0.001, 1.0, NODE_DELETE_BASE_FEE_USD, 1.0),

                // Submit with several signatures and the price should increase
                atomicBatch(nodeDelete("node100")
                                .payingWith("payer")
                                .signedBy("payer", "randomAccount", "testKey")
                                .sigMapPrefixes(uniqueWithFullPrefixesFor("payer", "randomAccount", "testKey"))
                                .hasKnownStatus(INVALID_SIGNATURE)
                                .via("multipleSigsDeletion")
                                .batchKey(BATCH_OPERATOR))
                        .via("atomic")
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                safeValidateInnerTxnChargedUsd(
                        "multipleSigsDeletion",
                        "atomic",
                        0.0011276316,
                        1.0,
                        NODE_DELETE_BASE_FEE_USD + 2 * SIGNATURE_FEE_AFTER_MULTIPLIER,
                        1.0),
                atomicBatch(nodeDelete("node100").via("deleteNode").batchKey(BATCH_OPERATOR))
                        .via("atomic")
                        .payingWith(BATCH_OPERATOR),
                getTxnRecord("deleteNode").logged(),
                // The fee is not charged here because the payer is privileged
                validateInnerTxnChargedUsd("deleteNode", "atomic", 0.0, 3.0));
    }

    @EmbeddedHapiTest(MUST_SKIP_INGEST)
    final Stream<DynamicTest> validateFeesInsufficientAmount() throws CertificateEncodingException {
        final String description = "His vorpal blade went snicker-snack!";
        final var nodeAccount = "nodeAccount";
        return hapiTest(
                newKeyNamed("testKey"),
                newKeyNamed("randomAccount"),
                cryptoCreate("payer").balance(10_000_000_000L),
                cryptoCreate(nodeAccount),
                nodeCreate("node100", nodeAccount)
                        .description(description)
                        .fee(ONE_HBAR)
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded()),
                // Submit to a different node so ingest check is skipped
                atomicBatch(nodeDelete("node100")
                                .fee(1)
                                .payingWith("payer")
                                .hasKnownStatus(INSUFFICIENT_TX_FEE)
                                .via("failedDeletion")
                                .batchKey(BATCH_OPERATOR))
                        .hasPrecheck(INSUFFICIENT_TX_FEE)
                        .payingWith(BATCH_OPERATOR),
                // Submit with several signatures and the price should increase
                atomicBatch(nodeDelete("node100")
                                .fee(ONE_HBAR)
                                .payingWith("payer")
                                .signedBy("payer", "randomAccount", "testKey")
                                .hasKnownStatus(INVALID_SIGNATURE)
                                .via("multipleSigsDeletion")
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(nodeDelete("node100").via("deleteNode").batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getTxnRecord("deleteNode").logged());
    }

    @EmbeddedHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> signWithCorrectAdminKeySuccess() throws CertificateEncodingException {
        final var nodeAccount = "nodeAccount";
        return hapiTest(
                newKeyNamed("payerKey"),
                cryptoCreate("payer").key("payerKey").balance(10_000_000_000L),
                newKeyNamed("adminKey"),
                cryptoCreate(nodeAccount),
                nodeCreate("testNode", nodeAccount)
                        .adminKey("adminKey")
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded()),
                atomicBatch(nodeDelete("testNode")
                                .payingWith("payer")
                                .signedBy("payer", "adminKey")
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                viewNode("testNode", node -> assertTrue(node.deleted(), "Node should be deleted")));
    }

    @EmbeddedHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> deleteNodeWorkWithValidAdminKey() throws CertificateEncodingException {
        final var nodeAccount = "nodeAccount";
        return hapiTest(
                newKeyNamed("adminKey"),
                cryptoCreate(nodeAccount),
                nodeCreate("testNode", nodeAccount)
                        .adminKey("adminKey")
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded()),
                viewNode("testNode", node -> assertFalse(node.deleted(), "Node should not be deleted")),
                atomicBatch(nodeDelete("testNode")
                                .signedBy(DEFAULT_PAYER, "adminKey")
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                viewNode("testNode", node -> assertTrue(node.deleted(), "Node should be deleted")));
    }

    @EmbeddedHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> deleteNodeWorkWithTreasuryPayer() throws CertificateEncodingException {
        final var nodeAccount = "nodeAccount";
        return hapiTest(
                newKeyNamed("adminKey"),
                cryptoCreate(nodeAccount),
                nodeCreate("testNode", nodeAccount)
                        .adminKey("adminKey")
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded()),
                viewNode("testNode", node -> assertFalse(node.deleted(), "Node should not be deleted")),
                atomicBatch(nodeDelete("testNode").payingWith(DEFAULT_PAYER).batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                viewNode("testNode", node -> assertTrue(node.deleted(), "Node should be deleted")));
    }

    @EmbeddedHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> deleteNodeWorkWithAddressBookAdminPayer() throws CertificateEncodingException {
        final var nodeAccount = "nodeAccount";
        return hapiTest(
                newKeyNamed("adminKey"),
                cryptoTransfer(tinyBarsFromTo(GENESIS, ADDRESS_BOOK_CONTROL, ONE_HUNDRED_HBARS))
                        .fee(ONE_HBAR),
                cryptoCreate(nodeAccount),
                nodeCreate("testNode", nodeAccount)
                        .adminKey("adminKey")
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded()),
                viewNode("testNode", node -> assertFalse(node.deleted(), "Node should not be deleted")),
                atomicBatch(nodeDelete("testNode")
                                .payingWith(ADDRESS_BOOK_CONTROL)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                viewNode("testNode", node -> assertTrue(node.deleted(), "Node should be deleted")));
    }

    @EmbeddedHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> deleteNodeWorkWithSysAdminPayer() throws CertificateEncodingException {
        final var nodeAccount = "nodeAccount";
        return hapiTest(
                newKeyNamed("adminKey"),
                cryptoCreate(nodeAccount),
                nodeCreate("testNode", nodeAccount)
                        .adminKey("adminKey")
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded()),
                viewNode("testNode", node -> assertFalse(node.deleted(), "Node should not be deleted")),
                atomicBatch(nodeDelete("testNode").payingWith(SYSTEM_ADMIN).batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                viewNode("testNode", node -> assertTrue(node.deleted(), "Node should be deleted")));
    }
}
