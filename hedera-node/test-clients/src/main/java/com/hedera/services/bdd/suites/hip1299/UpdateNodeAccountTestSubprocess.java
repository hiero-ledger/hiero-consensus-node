// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1299;

import static com.hedera.services.bdd.junit.TestTags.ONLY_SUBPROCESS;
import static com.hedera.services.bdd.junit.TestTags.SERIAL;
import static com.hedera.services.bdd.junit.hedera.ExternalPath.BLOCK_STREAMS_PARENT_DIR;
import static com.hedera.services.bdd.junit.hedera.ExternalPath.RECORD_STREAMS_DIR;
import static com.hedera.services.bdd.junit.hedera.utils.NetworkUtils.CLASSIC_FIRST_NODE_ACCOUNT_NUM;
import static com.hedera.services.bdd.spec.HapiPropertySource.asAccountString;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeUpdate;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.hip869.NodeCreateTest.generateX509Certificates;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_IS_LINKED_TO_A_NODE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_NODE_ACCOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.RECORD_NOT_FOUND;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.node.config.types.StreamMode;
import com.hedera.services.bdd.GenesisSubprocessTest;
import com.hedera.services.bdd.GenesisSubprocessTest.SubProcessNodeConfig;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hederahashgraph.api.proto.java.AccountID;
import edu.umd.cs.findbugs.annotations.NonNull;
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

        // This test asserts the node writes per-account stream directories to its own disk, so it needs a
        // file-writing node. It runs on a dedicated genesis subprocess network (no block node). We pin
        // writerMode=FILE_AND_GRPC on every node; streamMode is BOTH (not the BLOCKS production default)
        // because BLOCKS + gRPC streaming enables block-buffer backpressure, which would hang with no block
        // node to drain the buffer. BOTH disables backpressure while still writing both record and block
        // files to disk for the assertions below.
        @Tag(ONLY_SUBPROCESS)
        @HapiTest
        @GenesisSubprocessTest(
                subProcessNodeConfigs = {
                    @SubProcessNodeConfig(
                            nodeId = 0,
                            applicationPropertiesOverrides = {
                                "blockStream.streamMode", "BOTH",
                                "blockStream.writerMode", "FILE_AND_GRPC"
                            }),
                    @SubProcessNodeConfig(
                            nodeId = 1,
                            applicationPropertiesOverrides = {
                                "blockStream.streamMode", "BOTH",
                                "blockStream.writerMode", "FILE_AND_GRPC"
                            }),
                    @SubProcessNodeConfig(
                            nodeId = 2,
                            applicationPropertiesOverrides = {
                                "blockStream.streamMode", "BOTH",
                                "blockStream.writerMode", "FILE_AND_GRPC"
                            }),
                    @SubProcessNodeConfig(
                            nodeId = 3,
                            applicationPropertiesOverrides = {
                                "blockStream.streamMode", "BOTH",
                                "blockStream.writerMode", "FILE_AND_GRPC"
                            })
                })
        final Stream<DynamicTest> accountUpdateBuildsProperRecordPath() {
            final AtomicReference<AccountID> newAccountId = new AtomicReference<>();
            final AtomicReference<AccountID> oldNodeAccountId = new AtomicReference<>();
            final String nodeToUpdate = "3";

            return hapiTest(
                    cryptoCreate("newAccount").exposingCreatedIdTo(newAccountId::set),
                    // account 6 is the node account of node 3
                    getAccountInfo("6").exposingIdTo(oldNodeAccountId::set),
                    nodeUpdate(nodeToUpdate).accountId("newAccount").signedByPayerAnd("newAccount"),
                    // create a transaction after the update so stream files are generated
                    cryptoCreate("foo"),
                    // The output dir stays on the old node account until a restart; assert against whichever
                    // stream the node is producing for the current mode.
                    withOpContext((spec, log) -> {
                        // Resolve the stream dirs from the updated node's own (scope-aware) working dir: a
                        // @GenesisSubprocessTest network is scoped by method name, not the default "hapi"
                        // scope, so workingDirFor(..., null) would point at a non-existent path. The output
                        // dir stays on the old node account until a restart.
                        final var node = spec.getNetworkNodes().get(Integer.parseInt(nodeToUpdate));
                        final var streamMode = spec.startupProperties().getStreamMode("blockStream.streamMode");
                        if (streamMode != StreamMode.BLOCKS) {
                            final var recordsDir =
                                    node.getExternalPath(RECORD_STREAMS_DIR).getParent();
                            final var oldRecordPath =
                                    recordsDir.resolve("record" + asAccountString(oldNodeAccountId.get()));
                            final var newRecordPath =
                                    recordsDir.resolve("record" + asAccountString(newAccountId.get()));
                            assertTrue(oldRecordPath.toFile().exists());
                            assertFalse(newRecordPath.toFile().exists());
                        }
                        if (streamMode != StreamMode.RECORDS) {
                            final var blocksDir = node.getExternalPath(BLOCK_STREAMS_PARENT_DIR);
                            final var oldBlockPath =
                                    blocksDir.resolve("block-" + asAccountString(oldNodeAccountId.get()));
                            final var newBlockPath = blocksDir.resolve("block-" + asAccountString(newAccountId.get()));
                            assertTrue(oldBlockPath.toFile().exists());
                            assertFalse(newBlockPath.toFile().exists());
                        }
                    }));
        }
    }

    @Nested
    @Tag(SERIAL)
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
                    getTxnRecord("createTxn").hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND),
                    // Fund the original node account so it can be re-linked
                    cryptoTransfer(tinyBarsFromTo(DEFAULT_PAYER, String.valueOf(oldNodeAccountId), 1L)),
                    // Restore the original node account ID so other tests are not affected
                    nodeUpdate(String.valueOf(nodeIdToUpdate))
                            .accountId(String.valueOf(oldNodeAccountId))
                            .payingWith(DEFAULT_PAYER)
                            .signedByPayerAnd("newNodeAccount"));
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
