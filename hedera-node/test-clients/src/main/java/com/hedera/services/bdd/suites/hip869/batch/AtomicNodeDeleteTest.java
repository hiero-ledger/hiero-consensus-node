// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip869.batch;

import static com.hedera.services.bdd.junit.TestTags.ATOMIC_BATCH;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeDelete;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.hip869.NodeCreateTest.generateX509Certificates;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_NODE_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.NODE_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.NOT_SUPPORTED;

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

// This test cases are direct copies of NodeDeleteTest. The difference here is that
// we are wrapping the operations in an atomic batch to confirm that everything works as expected.
@Tag(ATOMIC_BATCH)
@HapiTestLifecycle
class AtomicNodeDeleteTest {

    private static List<X509Certificate> gossipCertificates;

    private static final String BATCH_OPERATOR = "batchOperator";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.doAdhoc(cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS));

        gossipCertificates = generateX509Certificates(1);
    }

    @HapiTest
    final Stream<DynamicTest> failsAtIngestForUnAuthorizedTxns() throws CertificateEncodingException {
        final String description = "His vorpal blade went snicker-snack!";
        final var nodeAccount = "nodeAccount";
        return hapiTest(
                cryptoCreate("payer").balance(10_000_000_000L),
                cryptoCreate(nodeAccount),
                nodeCreate("ntb", nodeAccount)
                        .description(description)
                        .fee(ONE_HBAR)
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded()),
                atomicBatch(nodeDelete("ntb")
                                .payingWith("payer")
                                .fee(ONE_HBAR)
                                .hasKnownStatus(INVALID_SIGNATURE)
                                .via("failedDeletion")
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> handleNodeNotExist() {
        final String nodeName = "33445566";
        return hapiTest(
                atomicBatch(nodeDelete(nodeName).hasKnownStatus(INVALID_NODE_ID).batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> handleNodeAlreadyDeleted() throws CertificateEncodingException {
        final String nodeName = "mytestnode";
        final var nodeAccount = "nodeAccount";
        return hapiTest(
                cryptoCreate(nodeAccount),
                nodeCreate(nodeName, nodeAccount)
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded()),
                atomicBatch(nodeDelete(nodeName).batchKey(BATCH_OPERATOR)).payingWith(BATCH_OPERATOR),
                atomicBatch(nodeDelete(nodeName)
                                .signedBy(GENESIS)
                                .hasKnownStatus(NODE_DELETED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> handleCanBeExecutedJustWithPrivilegedAccount() throws CertificateEncodingException {
        long PAYER_BALANCE = 1_999_999_999L;
        final String nodeName = "mytestnode";
        final var nodeAccount = "nodeAccount";
        return hapiTest(
                newKeyNamed("adminKey"),
                newKeyNamed("wrongKey"),
                cryptoCreate("payer").balance(PAYER_BALANCE).key("wrongKey"),
                cryptoCreate(nodeAccount),
                nodeCreate(nodeName, nodeAccount)
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded()),
                atomicBatch(nodeDelete(nodeName)
                                .payingWith("payer")
                                .signedBy("payer", "wrongKey")
                                .hasKnownStatus(INVALID_SIGNATURE)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatusFrom(INNER_TRANSACTION_FAILED),
                atomicBatch(nodeDelete(nodeName).batchKey(BATCH_OPERATOR)).payingWith(BATCH_OPERATOR));
    }

    @LeakyHapiTest(overrides = {"nodes.enableDAB"})
    @DisplayName("DAB enable test")
    final Stream<DynamicTest> checkDABEnable() throws CertificateEncodingException {
        final String nodeName = "mytestnode";
        final var nodeAccount = "nodeAccount";
        return hapiTest(
                cryptoCreate(nodeAccount),
                nodeCreate(nodeName, nodeAccount)
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded()),
                overriding("nodes.enableDAB", "false"),
                atomicBatch(nodeDelete(nodeName).hasPrecheck(NOT_SUPPORTED).batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasPrecheck(NOT_SUPPORTED));
    }

    @HapiTest
    final Stream<DynamicTest> signWithWrongAdminKeyFailed() throws CertificateEncodingException {
        final String nodeAccount = "nodeAccount";
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
                                .signedBy("payerKey")
                                .hasKnownStatus(INVALID_SIGNATURE)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }
}
