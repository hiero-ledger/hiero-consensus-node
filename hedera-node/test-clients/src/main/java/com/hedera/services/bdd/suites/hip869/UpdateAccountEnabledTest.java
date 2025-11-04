// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip869;

import static com.hedera.services.bdd.junit.ContextRequirement.THROTTLE_OVERRIDES;
import static com.hedera.services.bdd.junit.EmbeddedReason.MUST_SKIP_INGEST;
import static com.hedera.services.bdd.junit.EmbeddedReason.NEEDS_STATE_ACCESS;
import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.ONLY_SUBPROCESS;
import static com.hedera.services.bdd.junit.hedera.utils.AddressBookUtils.CLASSIC_FIRST_NODE_ACCOUNT_NUM;
import static com.hedera.services.bdd.spec.HapiPropertySource.asAccountString;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.keys.TrieSigMapGenerator.uniqueWithFullPrefixesFor;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeUpdate;
import static com.hedera.services.bdd.spec.utilops.EmbeddedVerbs.viewNode;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsdWithin;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.hip869.NodeCreateTest.generateX509Certificates;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_NODE_ACCOUNT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.services.bdd.junit.EmbeddedHapiTest;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyEmbeddedHapiTest;
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

/**
 * Tests expected behavior when the {@code nodes.updateAccountIdAllowed} feature flag is on for
 * <a href="https://hips.hedera.com/hip/hip-869">HIP-869, "Dynamic Address Book - Stage 1 - HAPI Endpoints"</a>.
 */
@HapiTestLifecycle
public class UpdateAccountEnabledTest {
    private static List<X509Certificate> gossipCertificates;

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of("nodes.updateAccountIdAllowed", "true"));
        gossipCertificates = generateX509Certificates(1);
    }

    @HapiTest
    final Stream<DynamicTest> updateEmptyAccountIdFail() throws CertificateEncodingException {
        final var nodeAccount = "nodeAccount";
        return hapiTest(
                newKeyNamed("adminKey"),
                cryptoCreate(nodeAccount),
                nodeCreate("testNode", nodeAccount)
                        .adminKey("adminKey")
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded()),
                nodeUpdate("testNode").accountId("").hasPrecheck(INVALID_NODE_ACCOUNT_ID));
    }

    @HapiTest
    final Stream<DynamicTest> updateAliasAccountIdFail() throws CertificateEncodingException {
        final var nodeAccount = "nodeAccount";
        return hapiTest(
                newKeyNamed("adminKey"),
                cryptoCreate(nodeAccount),
                nodeCreate("testNode", nodeAccount)
                        .adminKey("adminKey")
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded()),
                nodeUpdate("testNode").aliasAccountId("alias").hasPrecheck(INVALID_NODE_ACCOUNT_ID));
    }

    @LeakyEmbeddedHapiTest(
            reason = MUST_SKIP_INGEST,
            requirement = {THROTTLE_OVERRIDES},
            throttles = "testSystemFiles/mainnet-throttles.json")
    @Tag(MATS)
    final Stream<DynamicTest> validateFees() throws CertificateEncodingException {
        final String description = "His vorpal blade went snicker-snack!";
        final var nodeAccount = "nodeAccount";
        final var nodeAccount2 = "nodeAccount2";
        return hapiTest(
                newKeyNamed("testKey"),
                newKeyNamed("randomAccount"),
                cryptoCreate("payer").balance(10_000_000_000L),
                cryptoCreate(nodeAccount),
                cryptoCreate(nodeAccount2),
                nodeCreate("node100", nodeAccount)
                        .adminKey("testKey")
                        .description(description)
                        .fee(ONE_HBAR)
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded()),
                // Submit to a different node so ingest check is skipped
                nodeUpdate("node100")
                        .setNode(5)
                        .payingWith("payer")
                        .accountId(nodeAccount2)
                        .fee(ONE_HBAR)
                        .hasKnownStatus(INVALID_SIGNATURE)
                        .via("failedUpdate"),
                getTxnRecord("failedUpdate").logged(),
                // The fee is charged here because the payer is not privileged
                validateChargedUsdWithin("failedUpdate", 0.001, 3.0),
                nodeUpdate("node100")
                        .adminKey("testKey")
                        .accountId(nodeAccount2)
                        .signedByPayerAnd(nodeAccount2, "testKey")
                        .fee(ONE_HBAR)
                        .via("updateNode"),
                getTxnRecord("updateNode").logged(),
                // The fee is not charged here because the payer is privileged
                validateChargedUsdWithin("updateNode", 0.0, 3.0),

                // Submit with several signatures and the price should increase
                nodeUpdate("node100")
                        .setNode(5)
                        .payingWith("payer")
                        .signedBy("payer", "payer", "randomAccount", "testKey")
                        .sigMapPrefixes(uniqueWithFullPrefixesFor("payer", "randomAccount", "testKey"))
                        .fee(ONE_HBAR)
                        .via("failedUpdateMultipleSigs"),
                validateChargedUsdWithin("failedUpdateMultipleSigs", 0.0011276316, 3.0));
    }

    @EmbeddedHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> accountIdGetsUpdatedCorrectly() {
        final AtomicReference<AccountID> initialAccountId = new AtomicReference<>();
        final AtomicReference<AccountID> newAccountId = new AtomicReference<>();
        return hapiTest(
                newKeyNamed("adminKey"),
                cryptoCreate("initialNodeAccount").exposingCreatedIdTo(initialAccountId::set),
                cryptoCreate("newNodeAccount").exposingCreatedIdTo(newAccountId::set),
                sourcing(() -> {
                    try {
                        return nodeCreate("testNode", "initialNodeAccount")
                                .adminKey("adminKey")
                                .gossipCaCertificate(
                                        gossipCertificates.getFirst().getEncoded());
                    } catch (CertificateEncodingException e) {
                        throw new RuntimeException(e);
                    }
                }),
                sourcing(() -> nodeUpdate("testNode")
                        .accountId("newNodeAccount")
                        .signedByPayerAnd("newNodeAccount", "adminKey")),
                sourcing(() -> viewNode("testNode", node -> {
                    assertNotNull(node.accountId(), "Node accountId should not be null");
                    assertNotNull(node.accountId().accountNum(), "Node accountNum should not be null");
                    assertEquals(
                            node.accountId().accountNum(), newAccountId.get().getAccountNum());
                })));
    }

    @EmbeddedHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> updateAccountIdRequiredSignatures() {
        final AtomicReference<AccountID> initialNodeAccountId = new AtomicReference<>();
        final AtomicReference<AccountID> newAccountId = new AtomicReference<>();
        return hapiTest(
                newKeyNamed("adminKey"),
                cryptoCreate("initialNodeAccount").exposingCreatedIdTo(initialNodeAccountId::set),
                cryptoCreate("newAccount").exposingCreatedIdTo(newAccountId::set),
                sourcing(() -> {
                    try {
                        return nodeCreate("testNode", "initialNodeAccount")
                                .adminKey("adminKey")
                                .gossipCaCertificate(
                                        gossipCertificates.getFirst().getEncoded());
                    } catch (CertificateEncodingException e) {
                        throw new RuntimeException(e);
                    }
                }),
                // signed with correct sig fails if account is sentinel
                nodeUpdate("testNode")
                        .accountId("0.0.0")
                        .payingWith(DEFAULT_PAYER)
                        .signedByPayerAnd("initialNodeAccount")
                        .hasPrecheck(INVALID_NODE_ACCOUNT_ID),
                // signed with correct sig passes if account is valid
                nodeUpdate("testNode")
                        .accountId("newAccount")
                        .payingWith(DEFAULT_PAYER)
                        .signedByPayerAnd("adminKey", "newAccount"),
                viewNode("testNode", node -> assertEquals(toPbj(newAccountId.get()), node.accountId())),
                // signed without adminKey works if only updating accountId
                nodeUpdate("testNode")
                        .accountId("initialNodeAccount")
                        .payingWith(DEFAULT_PAYER)
                        .signedByPayerAnd("newAccount", "initialNodeAccount"),
                viewNode("testNode", node -> assertEquals(toPbj(initialNodeAccountId.get()), node.accountId())),
                // signed without adminKey fails if updating other fields too
                nodeUpdate("testNode")
                        .accountId("newAccount")
                        .description("updatedNode")
                        .payingWith(DEFAULT_PAYER)
                        .signedByPayerAnd("initialNodeAccount", "newAccount")
                        .hasPrecheck(INVALID_SIGNATURE),
                viewNode("testNode", node -> assertEquals(toPbj(initialNodeAccountId.get()), node.accountId())));
    }

    @EmbeddedHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> updateAccountIdIsIdempotent() {
        final AtomicReference<AccountID> initialNodeAccountId = new AtomicReference<>();
        final AtomicReference<AccountID> newAccountId = new AtomicReference<>();
        return hapiTest(
                newKeyNamed("adminKey"),
                cryptoCreate("initialNodeAccount").exposingCreatedIdTo(initialNodeAccountId::set),
                cryptoCreate("newAccount").exposingCreatedIdTo(newAccountId::set),
                sourcing(() -> {
                    try {
                        return nodeCreate("testNode", "initialNodeAccount")
                                .adminKey("adminKey")
                                .gossipCaCertificate(
                                        gossipCertificates.getFirst().getEncoded());
                    } catch (CertificateEncodingException e) {
                        throw new RuntimeException(e);
                    }
                }),
                nodeUpdate("testNode")
                        .accountId("newAccount")
                        .payingWith(DEFAULT_PAYER)
                        .signedByPayerAnd("adminKey", "newAccount"),
                viewNode("testNode", node -> assertEquals(toPbj(newAccountId.get()), node.accountId())),
                // node update with the same accountId should pass
                nodeUpdate("testNode")
                        .accountId("newAccount")
                        .payingWith(DEFAULT_PAYER)
                        .signedByPayerAnd("adminKey", "newAccount"),
                viewNode("testNode", node -> assertEquals(toPbj(newAccountId.get()), node.accountId())));
    }

    @HapiTest
    final Stream<DynamicTest> restrictNodeAccountDeletion() throws CertificateEncodingException {
        final var adminKey = "adminKey";
        final var account = "account";
        final var secondAccount = "secondAccount";
        final var node = "testNode";
        return hapiTest(
                cryptoCreate(account),
                cryptoCreate(secondAccount),
                newKeyNamed(adminKey),

                // create new node
                nodeCreate(node, account)
                        .adminKey("adminKey")
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded()),
                // verify we can't delete the node account
                cryptoDelete(account).hasKnownStatus(ACCOUNT_IS_LINKED_TO_A_NODE),

                // update the new node account id
                nodeUpdate(node)
                        .accountId(secondAccount)
                        .payingWith(secondAccount)
                        .signedBy(secondAccount, adminKey),

                // verify now we can delete the old node account, and can't delete the new node account
                cryptoDelete(account),
                cryptoDelete(secondAccount).hasKnownStatus(ACCOUNT_IS_LINKED_TO_A_NODE),

                // delete the node
                nodeDelete(node).signedByPayerAnd(adminKey),
                // verify we can delete the second account
                cryptoDelete(secondAccount));
    }

    @HapiTest
    final Stream<DynamicTest> nodeUpdateWithAccountLinkedToAnotherAccount() throws CertificateEncodingException {
        final var adminKey = "adminKey";
        final var account = "account";
        final var secondAccount = "secondAccount";
        final var node1 = "Node1";
        final var node2 = "Node2";
        return hapiTest(
                cryptoCreate(account),
                cryptoCreate(secondAccount),
                newKeyNamed(adminKey),
                nodeCreate(node1, account)
                        .adminKey("adminKey")
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded()),
                nodeCreate(node2, secondAccount)
                        .adminKey("adminKey")
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded()),
                // Verify node 1 update with second account will fail
                nodeUpdate(node1)
                        .accountId(secondAccount)
                        .signedByPayerAnd(secondAccount, adminKey)
                        .hasKnownStatus(ACCOUNT_IS_LINKED_TO_A_NODE),
                // clear nodes from state
                nodeDelete(node1).signedByPayerAnd(adminKey),
                nodeDelete(node2).signedByPayerAnd(adminKey));
    }

    @HapiTest
    final Stream<DynamicTest> nodeUpdateWithZeroBalanceAccount() throws CertificateEncodingException {
        final var adminKey = "adminKey";
        final var account = "account";
        final var zeroBalanceAccount = "zeroBalanceAccount";
        final var node = "testNode";
        return hapiTest(
                cryptoCreate(account),
                cryptoCreate(zeroBalanceAccount).balance(0L),
                newKeyNamed(adminKey),
                nodeCreate(node, account)
                        .adminKey("adminKey")
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded()),
                // Verify node update with zero balance account will fail
                nodeUpdate(node)
                        .accountId(zeroBalanceAccount)
                        .signedByPayerAnd(zeroBalanceAccount, adminKey)
                        .hasKnownStatus(NODE_ACCOUNT_HAS_ZERO_BALANCE),
                // Fund the account and try again
                cryptoTransfer(movingHbar(ONE_HBAR).between(GENESIS, zeroBalanceAccount)),
                nodeUpdate(node).accountId(zeroBalanceAccount).signedByPayerAnd(zeroBalanceAccount, adminKey),
                // clear nodes from state
                nodeDelete(node).signedByPayerAnd(adminKey));
    }

    @HapiTest
    final Stream<DynamicTest> nodeUpdateWithDeletedAccount() throws CertificateEncodingException {
        final var adminKey = "adminKey";
        final var account = "account";
        final var deletedAccount = "deletedAccount";
        final var node = "testNode";
        return hapiTest(
                cryptoCreate(account),
                newKeyNamed(adminKey),
                nodeCreate(node, account)
                        .adminKey("adminKey")
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded()),
                cryptoCreate(deletedAccount),
                cryptoDelete(deletedAccount),
                // Verify node update will fail
                nodeUpdate(node)
                        .accountId(deletedAccount)
                        .signedByPayerAnd(deletedAccount, adminKey)
                        .hasKnownStatus(ACCOUNT_DELETED));
    }

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
                    final var oldRecordPath = Paths.get(baseDir + "record" + asAccountString(oldNodeAccountId.get()));
                    final var newRecordPath = Paths.get(baseDir + "record" + asAccountString(newAccountId.get()));
                    assertTrue(oldRecordPath.toFile().exists());
                    assertFalse(newRecordPath.toFile().exists());
                }));
    }

    @HapiTest
    final Stream<DynamicTest> updateAccountToAdmin() throws CertificateEncodingException {
        final var adminKey = "adminKey";
        return hapiTest(
                newKeyNamed(adminKey),
                cryptoCreate("nodeAccount"),
                cryptoCreate("newAccount").key(adminKey),
                nodeCreate("testNode", "nodeAccount")
                        .adminKey(adminKey)
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded()),
                nodeUpdate("testNode").accountId("newAccount").signedByPayerAnd(adminKey));
    }

    @Nested
    public class NegativeCases {

        @HapiTest
        final Stream<DynamicTest> updateAccountNegSignatures() throws CertificateEncodingException {
            return hapiTest(
                    newKeyNamed("adminKey"),
                    cryptoCreate("nodeAccount"),
                    cryptoCreate("newAccount"),
                    nodeCreate("testNode", "nodeAccount")
                            .adminKey("adminKey")
                            .gossipCaCertificate(gossipCertificates.getFirst().getEncoded()),

                    // not signed by 'newAccount' so it should fail
                    nodeUpdate("testNode")
                            .accountId("newAccount")
                            .signedByPayerAnd("adminKey")
                            .hasKnownStatus(INVALID_SIGNATURE),
                    // not signed by 'adminKey' so it should fail
                    nodeUpdate("testNode")
                            .accountId("newAccount")
                            .signedByPayerAnd("newAccount")
                            .hasKnownStatus(INVALID_SIGNATURE),
                    // signed with both passes
                    nodeUpdate("testNode").accountId("newAccount").signedByPayerAnd("adminKey", "newAccount"));
        }

        @HapiTest
        final Stream<DynamicTest> updateAccountNegSignaturesWithoutAdminKey() throws CertificateEncodingException {
            return hapiTest(
                    newKeyNamed("adminKey"),
                    cryptoCreate("nodeAccount"),
                    cryptoCreate("newAccount"),
                    nodeCreate("testNode", "nodeAccount")
                            .adminKey("adminKey")
                            .gossipCaCertificate(gossipCertificates.getFirst().getEncoded()),

                    // not signed by 'newAccount' so it should fail
                    nodeUpdate("testNode")
                            .accountId("newAccount")
                            .signedByPayerAnd("nodeAccount")
                            .hasKnownStatus(INVALID_SIGNATURE),
                    // not signed by 'nodeAccount' so it should fail
                    nodeUpdate("testNode")
                            .accountId("newAccount")
                            .signedByPayerAnd("newAccount")
                            .hasKnownStatus(INVALID_SIGNATURE),
                    // signed with both passes
                    nodeUpdate("testNode").accountId("newAccount").signedByPayerAnd("nodeAccount", "newAccount"));
        }
    }
}
