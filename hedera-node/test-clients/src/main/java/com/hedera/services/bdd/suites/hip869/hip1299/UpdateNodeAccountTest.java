// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip869.hip1299;

import static com.hedera.services.bdd.junit.EmbeddedReason.NEEDS_STATE_ACCESS;
import static com.hedera.services.bdd.junit.TestTags.MATS;
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
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeUpdate;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.EmbeddedVerbs.viewNode;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsd;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.hip869.NodeCreateTest.generateX509Certificates;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_NODE_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.KEY_REQUIRED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.NODE_ACCOUNT_HAS_ZERO_BALANCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.hedera.services.bdd.junit.EmbeddedHapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.keys.SigControl;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;

@HapiTestLifecycle
@Tag(MATS)
public class UpdateNodeAccountTest {

    private static List<X509Certificate> gossipCertificates;

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of("nodes.updateAccountIdAllowed", "true"));
        gossipCertificates = generateX509Certificates(1);
    }

    @Nested
    class UpdateNodeAccountIdPositiveTests {
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
                    withOpContext((spec, log) -> allRunFor(
                            spec,
                            viewNode(
                                    "testNode",
                                    node -> assertEquals(
                                            newAccountId.get(),
                                            node.accountId().accountNum(),
                                            "Node accountId should be updated")))));
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
                    withOpContext((spec, log) -> allRunFor(
                            spec,
                            viewNode(
                                    "testNode",
                                    node -> assertEquals(
                                            newAccountId.get(),
                                            node.accountId().accountNum(),
                                            "Node accountId should be updated")))));
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
                    withOpContext((spec, log) -> allRunFor(
                            spec,
                            viewNode(
                                    "testNode",
                                    node -> assertEquals(
                                            newAccountId.get(),
                                            node.accountId().accountNum(),
                                            "Node accountId should be updated")))));
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
                    withOpContext((spec, log) -> allRunFor(
                            spec,
                            viewNode(
                                    "testNode",
                                    node -> assertEquals(
                                            newAccountId.get(),
                                            node.accountId().accountNum(),
                                            "Node accountId should be updated")))));
        }

        @EmbeddedHapiTest(NEEDS_STATE_ACCESS)
        final Stream<DynamicTest> setUpNewNodeAccountForNodeWithoutAdminKey() throws CertificateEncodingException {
            final String initialNodeAccount = "initialNodeAccount";
            final String newNodeAccount = "newNodeAccount";
            final var certificateBytes = gossipCertificates.getFirst().getEncoded();
            AtomicLong newAccountId = new AtomicLong();
            return hapiTest(
                    cryptoCreate(initialNodeAccount),
                    cryptoCreate(newNodeAccount).exposingCreatedIdTo(id -> newAccountId.set(id.getAccountNum())),
                    nodeCreate("testNode", initialNodeAccount).gossipCaCertificate(certificateBytes),
                    nodeUpdate("testNode")
                            .accountId(newNodeAccount)
                            .signedByPayerAnd(initialNodeAccount, newNodeAccount),
                    withOpContext((spec, log) -> allRunFor(
                            spec,
                            viewNode(
                                    "testNode",
                                    node -> assertEquals(
                                            newAccountId.get(),
                                            node.accountId().accountNum(),
                                            "Node accountId should be updated")))));
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
                    withOpContext((spec, log) -> allRunFor(
                            spec,
                            viewNode(
                                    "testNode",
                                    node -> assertEquals(
                                            newAccountId.get(),
                                            node.accountId().accountNum(),
                                            "Node accountId should be updated")))));
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
                    withOpContext((spec, log) -> allRunFor(
                            spec,
                            viewNode(
                                    "testNode",
                                    node -> assertEquals(
                                            newAccountId.get(),
                                            node.accountId().accountNum(),
                                            "Node accountId should be updated")))));
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
                    withOpContext((spec, log) -> allRunFor(
                            spec,
                            viewNode(
                                    "testNode",
                                    node -> assertEquals(
                                            newAccountId.get(),
                                            node.accountId().accountNum(),
                                            "Node accountId should be updated")))));
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
                    withOpContext((spec, log) -> allRunFor(
                            spec,
                            viewNode(
                                    "testNode",
                                    node -> assertEquals(
                                            newAccountId.get(),
                                            node.accountId().accountNum(),
                                            "Node accountId should be updated")))));
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
                    withOpContext((spec, log) -> allRunFor(
                            spec,
                            viewNode(
                                    "testNode",
                                    node -> assertEquals(
                                            newAccountId.get(),
                                            node.accountId().accountNum(),
                                            "Node accountId should be updated")))));
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
                    withOpContext((spec, log) -> allRunFor(
                            spec,
                            viewNode(
                                    "testNode",
                                    node -> assertEquals(
                                            newAccountId.get(),
                                            node.accountId().accountNum(),
                                            "Node accountId should be updated")))));
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
                    withOpContext((spec, log) -> allRunFor(
                            spec,
                            viewNode(
                                    "testNode",
                                    node -> assertEquals(
                                            newAccountId.get(),
                                            node.accountId().accountNum(),
                                            "Node accountId should be updated")))));
        }
    }

    @Nested
    class UpdateNodeAccountIdNegativeTests {
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
                    withOpContext((spec, log) -> allRunFor(
                            spec,
                            viewNode(
                                    "testNode",
                                    node -> assertNotEquals(
                                            newAccountId.get(),
                                            node.accountId().accountNum(),
                                            "Node accountId should not be updated")))));
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
                    withOpContext((spec, log) -> allRunFor(
                            spec,
                            viewNode(
                                    "testNode",
                                    node -> assertNotEquals(
                                            newAccountId.get(),
                                            node.accountId().accountNum(),
                                            "Node accountId should not be updated")))));
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
                    withOpContext((spec, log) -> allRunFor(
                            spec,
                            viewNode(
                                    "testNode",
                                    node -> assertNotEquals(
                                            newAccountId.get(),
                                            node.accountId().accountNum(),
                                            "Node accountId should not be updated")))));
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
                    withOpContext((spec, log) -> allRunFor(
                            spec,
                            viewNode(
                                    "testNode",
                                    node -> assertNotEquals(
                                            newAccountId.get(),
                                            node.accountId().accountNum(),
                                            "Node accountId should not be updated")))));
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
                    withOpContext((spec, log) -> allRunFor(
                            spec,
                            viewNode(
                                    "testNode",
                                    node -> assertNotEquals(
                                            newAccountId.get(),
                                            node.accountId().accountNum(),
                                            "Node accountId should not be updated")))));
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
                    withOpContext((spec, log) -> allRunFor(
                            spec,
                            viewNode(
                                    "testNode",
                                    node -> assertNotEquals(
                                            newAccountId.get(),
                                            node.accountId().accountNum(),
                                            "Node accountId should not be updated")))));
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
                    withOpContext((spec, log) -> allRunFor(
                            spec,
                            viewNode(
                                    "testNode",
                                    node -> assertNotEquals(
                                            newAccountId.get(),
                                            node.accountId().accountNum(),
                                            "Node accountId should not be updated")))));
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
                    withOpContext((spec, log) -> allRunFor(
                            spec,
                            viewNode(
                                    "testNode",
                                    node -> assertNotEquals(
                                            newAccountId.get(),
                                            node.accountId().accountNum(),
                                            "Node accountId should not be updated")))));
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
                    withOpContext((spec, log) -> allRunFor(
                            spec,
                            viewNode(
                                    "testNode",
                                    node -> assertNotEquals(
                                            newAccountId.get(),
                                            node.accountId().accountNum(),
                                            "Node accountId should not be updated")))));
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
                    withOpContext((spec, log) -> allRunFor(
                            spec,
                            viewNode(
                                    "testNode",
                                    node -> assertNotEquals(
                                            newAccountId.get(),
                                            node.accountId().accountNum(),
                                            "Node accountId should not be updated")))));
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
                    withOpContext((spec, log) -> allRunFor(
                            spec,
                            viewNode(
                                    "testNode",
                                    node -> assertNotEquals(
                                            newAccountId.get(),
                                            node.accountId().accountNum(),
                                            "Node accountId should not be updated")))));
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
                    withOpContext((spec, log) -> allRunFor(
                            spec,
                            viewNode(
                                    "testNode",
                                    node -> assertNotEquals(
                                            newAccountId.get(),
                                            node.accountId().accountNum(),
                                            "Node accountId should not be updated")))));
        }
    }
}
