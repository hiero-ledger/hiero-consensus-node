// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1299;

import static com.hedera.node.app.hapi.utils.CommonPbjConverters.toPbj;
import static com.hedera.services.bdd.junit.EmbeddedReason.NEEDS_STATE_ACCESS;
import static com.hedera.services.bdd.junit.TestTags.ONLY_EMBEDDED;
import static com.hedera.services.bdd.junit.hedera.embedded.EmbeddedMode.CONCURRENT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.keys.ControlForKey.forKey;
import static com.hedera.services.bdd.spec.keys.KeyShape.SIMPLE;
import static com.hedera.services.bdd.spec.keys.KeyShape.listOf;
import static com.hedera.services.bdd.spec.keys.KeyShape.sigs;
import static com.hedera.services.bdd.spec.keys.KeyShape.threshOf;
import static com.hedera.services.bdd.spec.keys.SigControl.OFF;
import static com.hedera.services.bdd.spec.keys.SigControl.ON;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createDefaultContract;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeUpdate;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.utilops.EmbeddedVerbs.viewNode;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsd;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.hip869.NodeCreateTest.generateX509Certificates;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_IS_LINKED_TO_A_NODE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_NODE_ACCOUNT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_NODE_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.KEY_REQUIRED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.NODE_ACCOUNT_HAS_ZERO_BALANCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.hedera.services.bdd.junit.EmbeddedHapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.TargetEmbeddedMode;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.keys.SigControl;
import com.hederahashgraph.api.proto.java.AccountID;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;

@Tag(ONLY_EMBEDDED)
@TargetEmbeddedMode(CONCURRENT)
@HapiTestLifecycle
public class UpdateNodeAccountTestEmbedded {

    private static List<X509Certificate> gossipCertificates;

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of("nodes.updateAccountIdAllowed", "true"));
        gossipCertificates = generateX509Certificates(1);
    }

    @Nested
    class UpdateNodeAccountIdPositiveTests {
        @EmbeddedHapiTest(NEEDS_STATE_ACCESS)
        final Stream<DynamicTest> updateAccountIdSuccessfullyHappyPath() throws CertificateEncodingException {
            final String initialNodeAccount = "initialNodeAccount";
            final String newNodeAccount = "newNodeAccount";
            final var certificateBytes = gossipCertificates.getFirst().getEncoded();
            AtomicLong newAccountId = new AtomicLong();
            return hapiTest(
                    newKeyNamed("adminKey"),
                    cryptoCreate(initialNodeAccount),
                    cryptoCreate(newNodeAccount).exposingCreatedIdTo(id -> newAccountId.set(id.getAccountNum())),
                    nodeCreate("testNode", initialNodeAccount)
                            .adminKey("adminKey")
                            .gossipCaCertificate(certificateBytes),
                    nodeUpdate("testNode")
                            .adminKey("adminKey")
                            .signedByPayerAnd(newNodeAccount, "adminKey")
                            .accountId(newNodeAccount),
                    viewNode(
                            "testNode",
                            node -> assertEquals(
                                    newAccountId.get(),
                                    node.accountIdOrThrow().accountNum(),
                                    "Node accountId should be updated")));
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

        @EmbeddedHapiTest(NEEDS_STATE_ACCESS)
        final Stream<DynamicTest> updateNodeAccountIdSuccessfullySignedByAllKeys() throws CertificateEncodingException {
            final String initialNodeAccount = "initialNodeAccount";
            final String newNodeAccount = "newNodeAccount";
            final String PAYER = "payer";
            final var certificateBytes = gossipCertificates.getFirst().getEncoded();
            AtomicLong newAccountId = new AtomicLong();
            return hapiTest(
                    newKeyNamed("adminKey"),
                    cryptoCreate(PAYER).balance(ONE_MILLION_HBARS),
                    cryptoCreate(initialNodeAccount),
                    cryptoCreate(newNodeAccount).exposingCreatedIdTo(id -> newAccountId.set(id.getAccountNum())),
                    nodeCreate("testNode", initialNodeAccount)
                            .adminKey("adminKey")
                            .gossipCaCertificate(certificateBytes),
                    nodeUpdate("testNode")
                            .accountId(newNodeAccount)
                            .payingWith(PAYER)
                            .signedBy(PAYER, initialNodeAccount, newNodeAccount, "adminKey")
                            .via("updateTxn"),
                    validateChargedUsd("updateTxn", 0.0012),
                    viewNode(
                            "testNode",
                            node -> assertEquals(
                                    newAccountId.get(),
                                    node.accountId().accountNum(),
                                    "Node accountId should be updated")));
        }

        @EmbeddedHapiTest(NEEDS_STATE_ACCESS)
        final Stream<DynamicTest> updateNodeAccountIdSuccessfullySignedByOldAndNewKeys()
                throws CertificateEncodingException {
            final String initialNodeAccount = "initialNodeAccount";
            final String newNodeAccount = "newNodeAccount";
            final var certificateBytes = gossipCertificates.getFirst().getEncoded();
            AtomicLong newAccountId = new AtomicLong();
            return hapiTest(
                    newKeyNamed("adminKey"),
                    cryptoCreate(initialNodeAccount),
                    cryptoCreate(newNodeAccount).exposingCreatedIdTo(id -> newAccountId.set(id.getAccountNum())),
                    nodeCreate("testNode", initialNodeAccount)
                            .adminKey("adminKey")
                            .gossipCaCertificate(certificateBytes),
                    nodeUpdate("testNode")
                            .accountId(newNodeAccount)
                            .signedByPayerAnd(initialNodeAccount, newNodeAccount),
                    viewNode(
                            "testNode",
                            node -> assertEquals(
                                    newAccountId.get(),
                                    node.accountId().accountNum(),
                                    "Node accountId should be updated")));
        }

        @EmbeddedHapiTest(NEEDS_STATE_ACCESS)
        final Stream<DynamicTest> updateNodeAccountIdSuccessfullySignedByNewAndAdminKeys()
                throws CertificateEncodingException {
            final String initialNodeAccount = "initialNodeAccount";
            final String newNodeAccount = "newNodeAccount";
            final var certificateBytes = gossipCertificates.getFirst().getEncoded();
            AtomicLong newAccountId = new AtomicLong();
            return hapiTest(
                    newKeyNamed("adminKey"),
                    cryptoCreate(initialNodeAccount),
                    cryptoCreate(newNodeAccount).exposingCreatedIdTo(id -> newAccountId.set(id.getAccountNum())),
                    nodeCreate("testNode", initialNodeAccount)
                            .adminKey("adminKey")
                            .gossipCaCertificate(certificateBytes),
                    nodeUpdate("testNode").accountId(newNodeAccount).signedByPayerAnd(newNodeAccount, "adminKey"),
                    viewNode(
                            "testNode",
                            node -> assertEquals(
                                    newAccountId.get(),
                                    node.accountId().accountNum(),
                                    "Node accountId should be updated")));
        }

        @EmbeddedHapiTest(NEEDS_STATE_ACCESS)
        final Stream<DynamicTest> setUpNodeAdminAccountAsNewNodeAccount() throws CertificateEncodingException {
            final String initialNodeAccount = "initialNodeAccount";
            final var adminAccount = "adminAccount";
            final var certificateBytes = gossipCertificates.getFirst().getEncoded();
            AtomicLong newAccountId = new AtomicLong();
            return hapiTest(
                    cryptoCreate(initialNodeAccount),
                    cryptoCreate(adminAccount).exposingCreatedIdTo(id -> newAccountId.set(id.getAccountNum())),
                    nodeCreate("testNode", initialNodeAccount)
                            .adminKey(adminAccount)
                            .gossipCaCertificate(certificateBytes),
                    nodeUpdate("testNode").accountId(adminAccount).signedByPayerAnd(adminAccount),
                    viewNode(
                            "testNode",
                            node -> assertEquals(
                                    newAccountId.get(),
                                    node.accountId().accountNum(),
                                    "Node accountId should be updated")));
        }

        @EmbeddedHapiTest(NEEDS_STATE_ACCESS)
        final Stream<DynamicTest> updateNodeAccountAdminKeyAndNodeAccountId() throws CertificateEncodingException {
            final String initialNodeAccount = "initialNodeAccount";
            final String newNodeAccount = "newNodeAccount";
            final var certificateBytes = gossipCertificates.getFirst().getEncoded();
            AtomicLong newAccountId = new AtomicLong();
            return hapiTest(
                    newKeyNamed("adminKey"),
                    newKeyNamed("newAdminKey"),
                    cryptoCreate(initialNodeAccount),
                    cryptoCreate(newNodeAccount).exposingCreatedIdTo(id -> newAccountId.set(id.getAccountNum())),
                    nodeCreate("testNode", initialNodeAccount)
                            .adminKey("adminKey")
                            .gossipCaCertificate(certificateBytes),
                    nodeUpdate("testNode")
                            .adminKey("newAdminKey")
                            .accountId(newNodeAccount)
                            .signedByPayerAnd(newNodeAccount, "adminKey", "newAdminKey"),
                    viewNode(
                            "testNode",
                            node -> assertEquals(
                                    newAccountId.get(),
                                    node.accountId().accountNum(),
                                    "Node accountId should be updated")));
        }

        @EmbeddedHapiTest(NEEDS_STATE_ACCESS)
        final Stream<DynamicTest> updateNodeAccountAdminKeyAndUpdateNodeAccountIdSeparately()
                throws CertificateEncodingException {
            final String initialNodeAccount = "initialNodeAccount";
            final String newNodeAccount = "newNodeAccount";
            final var certificateBytes = gossipCertificates.getFirst().getEncoded();
            AtomicLong newAccountId = new AtomicLong();
            return hapiTest(
                    newKeyNamed("adminKey"),
                    newKeyNamed("newAdminKey"),
                    cryptoCreate(initialNodeAccount),
                    cryptoCreate(newNodeAccount).exposingCreatedIdTo(id -> newAccountId.set(id.getAccountNum())),
                    nodeCreate("testNode", initialNodeAccount)
                            .adminKey("adminKey")
                            .gossipCaCertificate(certificateBytes),
                    nodeUpdate("testNode").adminKey("newAdminKey").signedByPayerAnd("adminKey", "newAdminKey"),
                    nodeUpdate("testNode").accountId(newNodeAccount).signedByPayerAnd(newNodeAccount, "newAdminKey"),
                    viewNode(
                            "testNode",
                            node -> assertEquals(
                                    newAccountId.get(),
                                    node.accountId().accountNum(),
                                    "Node accountId should be updated")));
        }

        @EmbeddedHapiTest(NEEDS_STATE_ACCESS)
        final Stream<DynamicTest> updateNodeAccountWithThresholdKeySuccessfullyWithNewNodeAccountID()
                throws CertificateEncodingException {
            final String initialNodeAccount = "initialNodeAccount";
            final String newNodeAccount = "newNodeAccount";
            final var certificateBytes = gossipCertificates.getFirst().getEncoded();
            AtomicLong newAccountId = new AtomicLong();
            // Define a threshold submit key that requires two simple keys signatures
            KeyShape thresholdKey = threshOf(2, SIMPLE, SIMPLE, listOf(2));

            // Create a valid signature with both simple keys signing
            SigControl validSig = thresholdKey.signedWith(sigs(ON, OFF, sigs(ON, ON)));

            return hapiTest(
                    newKeyNamed("adminKey"),
                    newKeyNamed("accountKey").shape(thresholdKey),
                    cryptoCreate(initialNodeAccount).key("accountKey"),
                    cryptoCreate(newNodeAccount).exposingCreatedIdTo(id -> newAccountId.set(id.getAccountNum())),
                    nodeCreate("testNode", initialNodeAccount)
                            .adminKey("adminKey")
                            .gossipCaCertificate(certificateBytes),
                    nodeUpdate("testNode")
                            .accountId(newNodeAccount)
                            .sigControl(forKey("accountKey", validSig))
                            .signedByPayerAnd(initialNodeAccount, newNodeAccount),
                    viewNode(
                            "testNode",
                            node -> assertEquals(
                                    newAccountId.get(),
                                    node.accountId().accountNum(),
                                    "Node accountId should be updated")));
        }

        @EmbeddedHapiTest(NEEDS_STATE_ACCESS)
        final Stream<DynamicTest> updateNodeAccountSuccessfullyWithNewNodeAccountWithThresholdKey()
                throws CertificateEncodingException {
            final String initialNodeAccount = "initialNodeAccount";
            final String newNodeAccount = "newNodeAccount";
            final var certificateBytes = gossipCertificates.getFirst().getEncoded();
            AtomicLong newAccountId = new AtomicLong();
            // Define a threshold submit key that requires two simple keys signatures
            KeyShape thresholdKey = threshOf(2, SIMPLE, SIMPLE, listOf(2));

            // Create a valid signature with both simple keys signing
            SigControl validSig = thresholdKey.signedWith(sigs(ON, OFF, sigs(ON, ON)));

            return hapiTest(
                    newKeyNamed("adminKey"),
                    newKeyNamed("accountKey").shape(thresholdKey),
                    cryptoCreate(initialNodeAccount),
                    cryptoCreate(newNodeAccount)
                            .key("accountKey")
                            .exposingCreatedIdTo(id -> newAccountId.set(id.getAccountNum())),
                    nodeCreate("testNode", initialNodeAccount)
                            .adminKey("adminKey")
                            .gossipCaCertificate(certificateBytes),
                    nodeUpdate("testNode")
                            .accountId(newNodeAccount)
                            .sigControl(forKey("accountKey", validSig))
                            .signedByPayerAnd(initialNodeAccount, newNodeAccount),
                    viewNode(
                            "testNode",
                            node -> assertEquals(
                                    newAccountId.get(),
                                    node.accountId().accountNum(),
                                    "Node accountId should be updated")));
        }

        @EmbeddedHapiTest(NEEDS_STATE_ACCESS)
        final Stream<DynamicTest> updateNodeAccountSuccessfullyWithNewNodeAccountWithKeyList()
                throws CertificateEncodingException {
            final String initialNodeAccount = "initialNodeAccount";
            final String newNodeAccount = "newNodeAccount";
            final var certificateBytes = gossipCertificates.getFirst().getEncoded();
            AtomicLong newAccountId = new AtomicLong();
            // Define a threshold submit key that requires two simple keys signatures
            KeyShape keyList3of3 = listOf(3);

            // Create a valid signature with all keys signing
            SigControl validSig = keyList3of3.signedWith(sigs(ON, ON, ON));

            return hapiTest(
                    newKeyNamed("adminKey"),
                    newKeyNamed("accountKey").shape(keyList3of3),
                    cryptoCreate(initialNodeAccount),
                    cryptoCreate(newNodeAccount)
                            .key("accountKey")
                            .exposingCreatedIdTo(id -> newAccountId.set(id.getAccountNum())),
                    nodeCreate("testNode", initialNodeAccount)
                            .adminKey("adminKey")
                            .gossipCaCertificate(certificateBytes),
                    nodeUpdate("testNode")
                            .accountId(newNodeAccount)
                            .sigControl(forKey("accountKey", validSig))
                            .signedByPayerAnd(initialNodeAccount, newNodeAccount),
                    viewNode(
                            "testNode",
                            node -> assertEquals(
                                    newAccountId.get(),
                                    node.accountId().accountNum(),
                                    "Node accountId should be updated")));
        }

        @EmbeddedHapiTest(NEEDS_STATE_ACCESS)
        final Stream<DynamicTest> updateNodeAccountIdSuccessfullyWithContractWithAdminKey()
                throws CertificateEncodingException {
            final String initialNodeAccount = "initialNodeAccount";
            final String PAYER = "payer";
            final String contractWithAdminKey = "nonCryptoAccount";
            final var certificateBytes = gossipCertificates.getFirst().getEncoded();
            AtomicLong newAccountId = new AtomicLong();
            return hapiTest(
                    newKeyNamed("adminKey"),
                    newKeyNamed("contractAdminKey"),
                    createDefaultContract(contractWithAdminKey)
                            .adminKey("contractAdminKey")
                            .exposingContractIdTo(id -> newAccountId.set(id.getContractNum())),
                    cryptoCreate(PAYER).balance(ONE_MILLION_HBARS),
                    cryptoCreate(initialNodeAccount),
                    nodeCreate("testNode", initialNodeAccount)
                            .adminKey("adminKey")
                            .gossipCaCertificate(certificateBytes),
                    cryptoTransfer(movingHbar(ONE_HBAR).between(PAYER, contractWithAdminKey)),
                    nodeUpdate("testNode")
                            .accountId(contractWithAdminKey)
                            .payingWith(PAYER)
                            .signedBy(PAYER, initialNodeAccount, contractWithAdminKey, "adminKey")
                            .via("updateTxn"),
                    validateChargedUsd("updateTxn", 0.0012),
                    viewNode(
                            "testNode",
                            node -> assertEquals(
                                    newAccountId.get(),
                                    node.accountId().accountNum(),
                                    "Node accountId should be updated")));
        }

        @EmbeddedHapiTest(NEEDS_STATE_ACCESS)
        final Stream<DynamicTest> updateNodeAccountIdSuccessfullyWithContractWithoutAdminKey()
                throws CertificateEncodingException {
            final String initialNodeAccount = "initialNodeAccount";
            final String PAYER = "payer";
            final String contractWithoutAdminKey = "nonCryptoAccount";
            final var certificateBytes = gossipCertificates.getFirst().getEncoded();
            AtomicLong newAccountId = new AtomicLong();
            return hapiTest(
                    newKeyNamed("adminKey"),
                    createDefaultContract(contractWithoutAdminKey)
                            .exposingContractIdTo(id -> newAccountId.set(id.getContractNum())),
                    cryptoCreate(PAYER).balance(ONE_MILLION_HBARS),
                    cryptoCreate(initialNodeAccount),
                    nodeCreate("testNode", initialNodeAccount)
                            .adminKey("adminKey")
                            .gossipCaCertificate(certificateBytes),
                    cryptoTransfer(movingHbar(ONE_HBAR).between(PAYER, contractWithoutAdminKey)),
                    nodeUpdate("testNode")
                            .accountId(contractWithoutAdminKey)
                            .payingWith(PAYER)
                            .signedBy(PAYER, initialNodeAccount, contractWithoutAdminKey, "adminKey")
                            .via("updateTxn"),
                    validateChargedUsd("updateTxn", 0.0012),
                    viewNode(
                            "testNode",
                            node -> assertEquals(
                                    newAccountId.get(),
                                    node.accountId().accountNum(),
                                    "Node accountId should be updated")));
        }

        @EmbeddedHapiTest(NEEDS_STATE_ACCESS)
        final Stream<DynamicTest> restrictNodeAccountDeletion() throws CertificateEncodingException {
            final var adminKey = "adminKey";
            final var account = "account";
            final var secondAccount = "secondAccount";
            final var node = "testNode";
            final var certificateBytes = gossipCertificates.getFirst().getEncoded();
            return hapiTest(
                    cryptoCreate(account),
                    cryptoCreate(secondAccount),
                    newKeyNamed(adminKey),

                    // create new node
                    nodeCreate(node, account).adminKey("adminKey").gossipCaCertificate(certificateBytes),
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
    }

    @Nested
    class UpdateNodeAccountIdNegativeTests {

        @EmbeddedHapiTest(NEEDS_STATE_ACCESS)
        final Stream<DynamicTest> nodeUpdateWithAccountLinkedToAnotherAccountFails()
                throws CertificateEncodingException {
            final var adminKey = "adminKey";
            final var account = "account";
            final var secondAccount = "secondAccount";
            final var node1 = "Node1";
            final var node2 = "Node2";
            final var certificateBytes = gossipCertificates.getFirst().getEncoded();
            return hapiTest(
                    cryptoCreate(account),
                    cryptoCreate(secondAccount),
                    newKeyNamed(adminKey),
                    nodeCreate(node1, account).adminKey("adminKey").gossipCaCertificate(certificateBytes),
                    nodeCreate(node2, secondAccount).adminKey("adminKey").gossipCaCertificate(certificateBytes),
                    // Verify node 1 update with second account will fail
                    nodeUpdate(node1)
                            .accountId(secondAccount)
                            .signedByPayerAnd(secondAccount, adminKey)
                            .hasKnownStatus(ACCOUNT_IS_LINKED_TO_A_NODE),
                    // clear nodes from state
                    nodeDelete(node1).signedByPayerAnd(adminKey),
                    nodeDelete(node2).signedByPayerAnd(adminKey));
        }

        @EmbeddedHapiTest(NEEDS_STATE_ACCESS)
        final Stream<DynamicTest> nodeUpdateWithZeroBalanceAccountFails() throws CertificateEncodingException {
            final var adminKey = "adminKey";
            final var account = "account";
            final var zeroBalanceAccount = "zeroBalanceAccount";
            final var node = "testNode";
            final var certificateBytes = gossipCertificates.getFirst().getEncoded();
            return hapiTest(
                    cryptoCreate(account),
                    cryptoCreate(zeroBalanceAccount).balance(0L),
                    newKeyNamed(adminKey),
                    nodeCreate(node, account).adminKey("adminKey").gossipCaCertificate(certificateBytes),
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

        @EmbeddedHapiTest(NEEDS_STATE_ACCESS)
        final Stream<DynamicTest> nodeUpdateWithDeletedAccountFails() throws CertificateEncodingException {
            final var adminKey = "adminKey";
            final var account = "account";
            final var deletedAccount = "deletedAccount";
            final var node = "testNode";
            final var certificateBytes = gossipCertificates.getFirst().getEncoded();
            return hapiTest(
                    cryptoCreate(account),
                    newKeyNamed(adminKey),
                    nodeCreate(node, account).adminKey("adminKey").gossipCaCertificate(certificateBytes),
                    cryptoCreate(deletedAccount),
                    cryptoDelete(deletedAccount),
                    // Verify node update will fail
                    nodeUpdate(node)
                            .accountId(deletedAccount)
                            .signedByPayerAnd(deletedAccount, adminKey)
                            .hasKnownStatus(ACCOUNT_DELETED));
        }

        @EmbeddedHapiTest(NEEDS_STATE_ACCESS)
        final Stream<DynamicTest> updateNodeAccountIdNotSignedByNewAccountFails() throws CertificateEncodingException {
            final String initialNodeAccount = "initialNodeAccount";
            final String newNodeAccount = "newNodeAccount";
            final var certificateBytes = gossipCertificates.getFirst().getEncoded();
            AtomicLong newAccountId = new AtomicLong();
            return hapiTest(
                    newKeyNamed("adminKey"),
                    cryptoCreate(initialNodeAccount),
                    cryptoCreate(newNodeAccount).exposingCreatedIdTo(id -> newAccountId.set(id.getAccountNum())),
                    nodeCreate("testNode", initialNodeAccount)
                            .adminKey("adminKey")
                            .gossipCaCertificate(certificateBytes),
                    nodeUpdate("testNode")
                            .accountId(newNodeAccount)
                            .signedByPayerAnd(initialNodeAccount, "adminKey")
                            .hasKnownStatus(INVALID_SIGNATURE),
                    viewNode(
                            "testNode",
                            node -> assertNotEquals(
                                    newAccountId.get(),
                                    node.accountId().accountNum(),
                                    "Node accountId should not be updated")));
        }

        @EmbeddedHapiTest(NEEDS_STATE_ACCESS)
        final Stream<DynamicTest> updateNodeAccountIdSignedByNewAccountOnlyFails() throws CertificateEncodingException {
            final String initialNodeAccount = "initialNodeAccount";
            final String newNodeAccount = "newNodeAccount";
            final var certificateBytes = gossipCertificates.getFirst().getEncoded();
            AtomicLong newAccountId = new AtomicLong();
            return hapiTest(
                    newKeyNamed("adminKey"),
                    cryptoCreate(initialNodeAccount),
                    cryptoCreate(newNodeAccount).exposingCreatedIdTo(id -> newAccountId.set(id.getAccountNum())),
                    nodeCreate("testNode", initialNodeAccount)
                            .adminKey("adminKey")
                            .gossipCaCertificate(certificateBytes),
                    nodeUpdate("testNode")
                            .accountId(newNodeAccount)
                            .signedByPayerAnd(newNodeAccount)
                            .hasKnownStatus(INVALID_SIGNATURE),
                    viewNode(
                            "testNode",
                            node -> assertNotEquals(
                                    newAccountId.get(),
                                    node.accountId().accountNum(),
                                    "Node accountId should not be updated")));
        }

        @EmbeddedHapiTest(NEEDS_STATE_ACCESS)
        final Stream<DynamicTest> updateNodeAccountIdSignedByOldAccountOnlyFails() throws CertificateEncodingException {
            final String initialNodeAccount = "initialNodeAccount";
            final String newNodeAccount = "newNodeAccount";
            final var certificateBytes = gossipCertificates.getFirst().getEncoded();
            AtomicLong newAccountId = new AtomicLong();
            return hapiTest(
                    newKeyNamed("adminKey"),
                    cryptoCreate(initialNodeAccount),
                    cryptoCreate(newNodeAccount).exposingCreatedIdTo(id -> newAccountId.set(id.getAccountNum())),
                    nodeCreate("testNode", initialNodeAccount)
                            .adminKey("adminKey")
                            .gossipCaCertificate(certificateBytes),
                    nodeUpdate("testNode")
                            .accountId(newNodeAccount)
                            .signedByPayerAnd(initialNodeAccount)
                            .hasKnownStatus(INVALID_SIGNATURE),
                    viewNode(
                            "testNode",
                            node -> assertNotEquals(
                                    newAccountId.get(),
                                    node.accountId().accountNum(),
                                    "Node accountId should not be updated")));
        }

        @EmbeddedHapiTest(NEEDS_STATE_ACCESS)
        final Stream<DynamicTest> updateNodeAccountAdminKeyNotSignedByNewAdminKeyFails()
                throws CertificateEncodingException {
            final String initialNodeAccount = "initialNodeAccount";
            final String newNodeAccount = "newNodeAccount";
            final var certificateBytes = gossipCertificates.getFirst().getEncoded();
            AtomicLong newAccountId = new AtomicLong();
            return hapiTest(
                    newKeyNamed("adminKey"),
                    newKeyNamed("newAdminKey"),
                    cryptoCreate(initialNodeAccount),
                    cryptoCreate(newNodeAccount).exposingCreatedIdTo(id -> newAccountId.set(id.getAccountNum())),
                    nodeCreate("testNode", initialNodeAccount)
                            .adminKey("adminKey")
                            .gossipCaCertificate(certificateBytes),
                    nodeUpdate("testNode")
                            .adminKey("newAdminKey")
                            .accountId(newNodeAccount)
                            .signedByPayerAnd(newNodeAccount, "adminKey")
                            .hasKnownStatus(INVALID_SIGNATURE),
                    viewNode(
                            "testNode",
                            node -> assertNotEquals(
                                    newAccountId.get(),
                                    node.accountId().accountNum(),
                                    "Node accountId should not be updated")));
        }

        @EmbeddedHapiTest(NEEDS_STATE_ACCESS)
        final Stream<DynamicTest> updateNodeAccountAdminKeyNotSignedByOldAdminKeyFails()
                throws CertificateEncodingException {
            final String initialNodeAccount = "initialNodeAccount";
            final String newNodeAccount = "newNodeAccount";
            final var certificateBytes = gossipCertificates.getFirst().getEncoded();
            AtomicLong newAccountId = new AtomicLong();
            return hapiTest(
                    newKeyNamed("adminKey"),
                    newKeyNamed("newAdminKey"),
                    cryptoCreate(initialNodeAccount),
                    cryptoCreate(newNodeAccount).exposingCreatedIdTo(id -> newAccountId.set(id.getAccountNum())),
                    nodeCreate("testNode", initialNodeAccount)
                            .adminKey("adminKey")
                            .gossipCaCertificate(certificateBytes),
                    nodeUpdate("testNode")
                            .adminKey("newAdminKey")
                            .accountId(newNodeAccount)
                            .signedByPayerAnd(newNodeAccount, "newAdminKey")
                            .hasKnownStatus(INVALID_SIGNATURE),
                    viewNode(
                            "testNode",
                            node -> assertNotEquals(
                                    newAccountId.get(),
                                    node.accountId().accountNum(),
                                    "Node accountId should not be updated")));
        }

        @EmbeddedHapiTest(NEEDS_STATE_ACCESS)
        final Stream<DynamicTest> updateNodeAccountAdminKeyAndUpdateNodeAccountIdSeparatelyNotSignedByNewAdminKeyFails()
                throws CertificateEncodingException {
            final String initialNodeAccount = "initialNodeAccount";
            final String newNodeAccount = "newNodeAccount";
            final var certificateBytes = gossipCertificates.getFirst().getEncoded();
            AtomicLong newAccountId = new AtomicLong();
            return hapiTest(
                    newKeyNamed("adminKey"),
                    newKeyNamed("newAdminKey"),
                    cryptoCreate(initialNodeAccount),
                    cryptoCreate(newNodeAccount).exposingCreatedIdTo(id -> newAccountId.set(id.getAccountNum())),
                    nodeCreate("testNode", initialNodeAccount)
                            .adminKey("adminKey")
                            .gossipCaCertificate(certificateBytes),
                    nodeUpdate("testNode").adminKey("newAdminKey").signedByPayerAnd("adminKey", "newAdminKey"),
                    nodeUpdate("testNode")
                            .accountId(newNodeAccount)
                            .signedByPayerAnd(newNodeAccount, "adminKey")
                            .hasKnownStatus(INVALID_SIGNATURE),
                    viewNode(
                            "testNode",
                            node -> assertNotEquals(
                                    newAccountId.get(),
                                    node.accountId().accountNum(),
                                    "Node accountId should not be updated")));
        }

        @EmbeddedHapiTest(NEEDS_STATE_ACCESS)
        final Stream<DynamicTest> updateNodeAccountWithThresholdKeyWithNewNodeAccountNotSignedByRequiredThresholdFails()
                throws CertificateEncodingException {
            final String initialNodeAccount = "initialNodeAccount";
            final String newNodeAccount = "newNodeAccount";
            final var certificateBytes = gossipCertificates.getFirst().getEncoded();
            AtomicLong newAccountId = new AtomicLong();
            // Define a threshold submit key that requires two simple keys signatures
            KeyShape thresholdKey = threshOf(2, SIMPLE, SIMPLE, listOf(2));

            // Create invalid signature with one simple key signing
            SigControl invalidSig = thresholdKey.signedWith(sigs(ON, OFF, sigs(ON, OFF)));

            return hapiTest(
                    newKeyNamed("adminKey"),
                    newKeyNamed("accountKey").shape(thresholdKey),
                    cryptoCreate(initialNodeAccount).key("accountKey"),
                    cryptoCreate(newNodeAccount).exposingCreatedIdTo(id -> newAccountId.set(id.getAccountNum())),
                    nodeCreate("testNode", initialNodeAccount)
                            .adminKey("adminKey")
                            .gossipCaCertificate(certificateBytes),
                    nodeUpdate("testNode")
                            .accountId(newNodeAccount)
                            .sigControl(forKey("accountKey", invalidSig))
                            .signedByPayerAnd(initialNodeAccount, newNodeAccount)
                            .hasKnownStatus(INVALID_SIGNATURE),
                    viewNode(
                            "testNode",
                            node -> assertNotEquals(
                                    newAccountId.get(),
                                    node.accountId().accountNum(),
                                    "Node accountId should not be updated")));
        }

        @EmbeddedHapiTest(NEEDS_STATE_ACCESS)
        final Stream<DynamicTest>
                updateNodeAccountWithNewNodeAccountWithThresholdKeyNodSignedWithRequiredThresholdFails()
                        throws CertificateEncodingException {
            final String initialNodeAccount = "initialNodeAccount";
            final String newNodeAccount = "newNodeAccount";
            final var certificateBytes = gossipCertificates.getFirst().getEncoded();
            AtomicLong newAccountId = new AtomicLong();
            // Define a threshold submit key that requires two simple keys signatures
            KeyShape thresholdKey = threshOf(2, SIMPLE, SIMPLE, listOf(2));

            // Create a valid signature with both simple keys signing
            SigControl invalidSig = thresholdKey.signedWith(sigs(ON, OFF, sigs(ON, OFF)));

            return hapiTest(
                    newKeyNamed("adminKey"),
                    newKeyNamed("accountKey").shape(thresholdKey),
                    cryptoCreate(initialNodeAccount),
                    cryptoCreate(newNodeAccount)
                            .key("accountKey")
                            .exposingCreatedIdTo(id -> newAccountId.set(id.getAccountNum())),
                    nodeCreate("testNode", initialNodeAccount)
                            .adminKey("adminKey")
                            .gossipCaCertificate(certificateBytes),
                    nodeUpdate("testNode")
                            .accountId(newNodeAccount)
                            .sigControl(forKey("accountKey", invalidSig))
                            .signedByPayerAnd(initialNodeAccount, newNodeAccount)
                            .hasKnownStatus(INVALID_SIGNATURE),
                    viewNode(
                            "testNode",
                            node -> assertNotEquals(
                                    newAccountId.get(),
                                    node.accountId().accountNum(),
                                    "Node accountId should not be updated")));
        }

        @EmbeddedHapiTest(NEEDS_STATE_ACCESS)
        final Stream<DynamicTest> updateNodeAccountWithNewNodeAccountWithKeyListNotSignedWithRequiredKeysFails()
                throws CertificateEncodingException {
            final String initialNodeAccount = "initialNodeAccount";
            final String newNodeAccount = "newNodeAccount";
            final var certificateBytes = gossipCertificates.getFirst().getEncoded();
            AtomicLong newAccountId = new AtomicLong();
            // Define a threshold submit key that requires two simple keys signatures
            KeyShape keyList3of3 = listOf(3);

            // Create invalid signature not with all required keys signing
            SigControl invalidSig = keyList3of3.signedWith(sigs(ON, ON, OFF));

            return hapiTest(
                    newKeyNamed("adminKey"),
                    newKeyNamed("accountKey").shape(keyList3of3),
                    cryptoCreate(initialNodeAccount),
                    cryptoCreate(newNodeAccount)
                            .key("accountKey")
                            .exposingCreatedIdTo(id -> newAccountId.set(id.getAccountNum())),
                    nodeCreate("testNode", initialNodeAccount)
                            .adminKey("adminKey")
                            .gossipCaCertificate(certificateBytes),
                    nodeUpdate("testNode")
                            .accountId(newNodeAccount)
                            .sigControl(forKey("accountKey", invalidSig))
                            .signedByPayerAnd(initialNodeAccount, newNodeAccount)
                            .hasKnownStatus(INVALID_SIGNATURE),
                    viewNode(
                            "testNode",
                            node -> assertNotEquals(
                                    newAccountId.get(),
                                    node.accountId().accountNum(),
                                    "Node accountId should not be updated")));
        }

        @EmbeddedHapiTest(NEEDS_STATE_ACCESS)
        final Stream<DynamicTest> updateNodeAccountIdWithContractWithAdminKeyWithZeroBalanceFails()
                throws CertificateEncodingException {
            final String initialNodeAccount = "initialNodeAccount";
            final String PAYER = "payer";
            final String contractWithAdminKey = "nonCryptoAccount";
            final var certificateBytes = gossipCertificates.getFirst().getEncoded();
            AtomicLong newAccountId = new AtomicLong();
            return hapiTest(
                    newKeyNamed("adminKey"),
                    newKeyNamed("contractAdminKey"),
                    createDefaultContract(contractWithAdminKey)
                            .adminKey("contractAdminKey")
                            .exposingContractIdTo(id -> newAccountId.set(id.getContractNum())),
                    cryptoCreate(PAYER).balance(ONE_MILLION_HBARS),
                    cryptoCreate(initialNodeAccount),
                    nodeCreate("testNode", initialNodeAccount)
                            .adminKey("adminKey")
                            .gossipCaCertificate(certificateBytes),
                    nodeUpdate("testNode")
                            .accountId(contractWithAdminKey)
                            .payingWith(PAYER)
                            .signedBy(PAYER, initialNodeAccount, contractWithAdminKey, "adminKey")
                            .via("updateTxn")
                            .hasKnownStatus(NODE_ACCOUNT_HAS_ZERO_BALANCE),
                    validateChargedUsd("updateTxn", 0.0012),
                    viewNode(
                            "testNode",
                            node -> assertNotEquals(
                                    newAccountId.get(),
                                    node.accountId().accountNum(),
                                    "Node accountId should not be updated")));
        }

        @EmbeddedHapiTest(NEEDS_STATE_ACCESS)
        final Stream<DynamicTest> updateNodeAfterNodeIsDeletedFails() throws CertificateEncodingException {
            final String initialNodeAccount = "initialNodeAccount";
            final String newNodeAccount = "newNodeAccount";
            final var certificateBytes = gossipCertificates.getFirst().getEncoded();
            AtomicLong newAccountId = new AtomicLong();
            return hapiTest(
                    newKeyNamed("adminKey"),
                    cryptoCreate(initialNodeAccount),
                    cryptoCreate(newNodeAccount).exposingCreatedIdTo(id -> newAccountId.set(id.getAccountNum())),
                    nodeCreate("testNode", initialNodeAccount)
                            .adminKey("adminKey")
                            .gossipCaCertificate(certificateBytes),

                    // Delete the node
                    nodeDelete("testNode").signedByPayerAnd("adminKey"),
                    nodeUpdate("testNode")
                            .accountId(newNodeAccount)
                            .signedByPayerAnd(initialNodeAccount, "adminKey")
                            .hasKnownStatus(INVALID_NODE_ID),
                    viewNode(
                            "testNode",
                            node -> assertNotEquals(
                                    newAccountId.get(),
                                    node.accountId().accountNum(),
                                    "Node accountId should not be updated")));
        }

        @EmbeddedHapiTest(NEEDS_STATE_ACCESS)
        final Stream<DynamicTest> updateNodeAccountAdminKeyToEmptyKeyListFails() throws CertificateEncodingException {
            final String initialNodeAccount = "initialNodeAccount";
            final String newNodeAccount = "newNodeAccount";
            final var certificateBytes = gossipCertificates.getFirst().getEncoded();
            AtomicLong newAccountId = new AtomicLong();
            return hapiTest(
                    newKeyNamed("adminKey"),
                    cryptoCreate(initialNodeAccount),
                    cryptoCreate(newNodeAccount).exposingCreatedIdTo(id -> newAccountId.set(id.getAccountNum())),
                    nodeCreate("testNode", initialNodeAccount)
                            .adminKey("adminKey")
                            .gossipCaCertificate(certificateBytes),

                    // Create empty key list
                    newKeyNamed("emptyKeyList").shape(listOf(0)),
                    nodeUpdate("testNode")
                            .adminKey("emptyKeyList")
                            .accountId(newNodeAccount)
                            .signedByPayerAnd(initialNodeAccount, "adminKey")
                            .hasPrecheck(KEY_REQUIRED),
                    viewNode(
                            "testNode",
                            node -> assertNotEquals(
                                    newAccountId.get(),
                                    node.accountIdOrThrow().accountNum(),
                                    "Node accountId should not be updated")));
        }
    }
}
