// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1299;

import static com.hedera.services.bdd.junit.TestTags.ONLY_SUBPROCESS;
import static com.hedera.services.bdd.junit.hedera.utils.NetworkUtils.CLASSIC_FIRST_NODE_ACCOUNT_NUM;
import static com.hedera.services.bdd.spec.HapiPropertySource.asAccountString;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeUpdate;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.hip869.NodeCreateTest.generateX509Certificates;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_IS_LINKED_TO_A_NODE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_NODE_ACCOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.RECORD_NOT_FOUND;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hederahashgraph.api.proto.java.AccountID;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Paths;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;

// nodes.updateAccountIdAllowed is true by default so it is safe to run this concurrently
@HapiTestLifecycle
public class UpdateNodeAccountTestSubprocess {

    private static List<X509Certificate> gossipCertificates;

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of("nodes.updateAccountIdAllowed", "true"));
        gossipCertificates = generateX509Certificates(1);
    }

    @Nested
    class UpdateNodeAccountIdPositiveTests {

        @Tag(ONLY_SUBPROCESS)
        @HapiTest
        final Stream<DynamicTest> accountUpdateBuildsProperRecordPath() {
            final AtomicReference<AccountID> newAccountId = new AtomicReference<>();
            final AtomicReference<AccountID> oldNodeAccountId = new AtomicReference<>();
            final String nodeToUpdate = "3";
            final String baseDir = "build/hapi-test/node" + nodeToUpdate + "/data/recordStreams/";

            return hapiTest(
                    cryptoCreate("newAccount").exposingCreatedIdTo(newAccountId::set),
                    // account 6 is the node account of node 3
                    getAccountInfo("6").exposingIdTo(oldNodeAccountId::set),
                    nodeUpdate(nodeToUpdate).accountId("newAccount").signedByPayerAnd("newAccount"),
                    // create a transaction after the update so record files are generated
                    cryptoCreate("foo"),
                    // assert record paths
                    withOpContext((spec, log) -> {
                        final var oldRecordPath =
                                Paths.get(baseDir + "record" + asAccountString(oldNodeAccountId.get()));
                        final var newRecordPath = Paths.get(baseDir + "record" + asAccountString(newAccountId.get()));
                        assertTrue(oldRecordPath.toFile().exists());
                        assertFalse(newRecordPath.toFile().exists());
                    }));
        }
    }

    @Nested
    class UpdateNodeAccountIdNegativeTests {
        @Tag(ONLY_SUBPROCESS)
        @HapiTest
        final Stream<DynamicTest> updateAccountIdAndSubmitWithOldAccountIdFails() {
            final var nodeIdToUpdate = 1;
            final var oldNodeAccountId = nodeIdToUpdate + CLASSIC_FIRST_NODE_ACCOUNT_NUM;
            return hapiTest(
                    cryptoCreate("newNodeAccount"),
                    // Node update works with nodeId not accountId,
                    // so we are updating the node we are submitting to
                    nodeUpdate(String.valueOf(nodeIdToUpdate))
                            .accountId("newNodeAccount")
                            .payingWith(DEFAULT_PAYER)
                            .signedByPayerAnd("newNodeAccount"),
                    cryptoCreate("foo")
                            .setNode(oldNodeAccountId)
                            .hasPrecheck(INVALID_NODE_ACCOUNT)
                            .via("createTxn"),
                    // Assert that the transaction was not submitted and failed on ingest
                    getTxnRecord("createTxn").hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
        }
    }

    @HapiTest
    final Stream<DynamicTest> testUnlinkAndLinkAccounts() throws CertificateEncodingException {
        return hapiTest(
                newKeyNamed("adminKey"),
                cryptoCreate("node1Account"),
                cryptoCreate("node2Account"),
                cryptoCreate("placeHolder"),
                nodeCreate("testNode1", "node1Account")
                        .adminKey("adminKey")
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded()),
                nodeCreate("testNode2", "node2Account")
                        .adminKey("adminKey")
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded()),
                nodeUpdate("testNode1").accountId("placeHolder").signedByPayerAnd("adminKey", "placeHolder"),
                nodeUpdate("testNode2").accountId("node1Account").signedByPayerAnd("adminKey", "node1Account"),
                cryptoDelete("placeHolder").hasKnownStatus(ACCOUNT_IS_LINKED_TO_A_NODE),
                cryptoDelete("node1Account").hasKnownStatus(ACCOUNT_IS_LINKED_TO_A_NODE),
                cryptoDelete("node2Account"));
    }
}
