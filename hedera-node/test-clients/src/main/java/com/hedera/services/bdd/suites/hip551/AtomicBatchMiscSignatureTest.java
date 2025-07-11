// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip551;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.submitMessageTo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAirdrop;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCancelAirdrop;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenClaimAirdrop;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedConsensusHbarFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.hbarLimit;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.maxCustomFee;
import static com.hedera.services.bdd.spec.transactions.token.HapiTokenClaimAirdrop.pendingAirdrop;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.hip869.NodeCreateTest.generateX509Certificates;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;

@HapiTestLifecycle
public class AtomicBatchMiscSignatureTest {

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(
                Map.of("atomicBatch.isEnabled", "true", "atomicBatch.maxNumberOfTransactions", "50"));
    }

    @Nested
    @DisplayName("Airdrop Tests")
    class AirdropTests {

        @HapiTest
        @DisplayName("airdrop with missing sender's signature fails")
        final Stream<DynamicTest> missingSenderSigFails() {
            final var airdropOp = tokenAirdrop(moving(1, "FT").between("owner", "receiver"))
                    .payingWith(DEFAULT_PAYER)
                    .batchKey("batchOperator");

            return hapiTest(
                    cryptoCreate("batchOperator"),
                    cryptoCreate("owner"),
                    cryptoCreate("receiver").maxAutomaticTokenAssociations(-1),
                    tokenCreate("FT").treasury("owner"),
                    // The owner is not signing the airdrop transaction, which should fail
                    atomicBatch(airdropOp).payingWith("batchOperator").hasKnownStatus(INNER_TRANSACTION_FAILED));
        }

        @HapiTest
        @DisplayName("claim airdrop with missing sender's signature fails")
        final Stream<DynamicTest> claimSignedNotByTheReceiverFails() {
            return hapiTest(
                    cryptoCreate("batchOperator"),
                    cryptoCreate("owner"),
                    cryptoCreate("receiver").maxAutomaticTokenAssociations(0),
                    cryptoCreate("fakeReceiver").maxAutomaticTokenAssociations(-1),
                    tokenCreate("FT").treasury("owner"),
                    tokenAirdrop(moving(1, "FT").between("owner", "receiver")).payingWith("owner"),
                    // Verify the airdrop is pending
                    getAccountBalance("receiver").hasTokenBalance("FT", 0L),
                    atomicBatch(
                                    // Payer is not the receiver
                                    tokenClaimAirdrop(pendingAirdrop("owner", "receiver", "FT"))
                                            .payingWith("fakeReceiver")
                                            .batchKey("batchOperator"))
                            .payingWith("batchOperator")
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),
                    // Verify the receiver did not receive the airdrop
                    getAccountBalance("receiver").hasTokenBalance("FT", 0L));
        }

        @HapiTest
        @DisplayName("Cancel not signed by the owner")
        final Stream<DynamicTest> cancelTokenInAirdrop() {
            return hapiTest(
                    cryptoCreate("batchOperator"),
                    cryptoCreate("owner"),
                    cryptoCreate("receiver").maxAutomaticTokenAssociations(0),
                    cryptoCreate("fakeReceiver").maxAutomaticTokenAssociations(-1),
                    tokenCreate("FT").treasury("owner"),
                    tokenAirdrop(moving(1, "FT").between("owner", "receiver")).payingWith("owner"),
                    // Verify the airdrop is pending
                    getAccountBalance("receiver").hasTokenBalance("FT", 0L),
                    atomicBatch(
                                    // Payer is not the owner
                                    tokenCancelAirdrop(pendingAirdrop("owner", "receiver", "FT"))
                                            .signedBy("receiver")
                                            .batchKey("batchOperator"))
                            .payingWith("batchOperator")
                            .hasPrecheck(INVALID_SIGNATURE));
        }
    }

    @Nested
    @DisplayName("Address Book Updates")
    class AddressBookUpdates {
        private static List<X509Certificate> gossipCertificates;

        @BeforeAll
        static void beforeAll() {
            gossipCertificates = generateX509Certificates(1);
        }

        @HapiTest
        @DisplayName("Node delete inside of a batch can be executed only with privileged account")
        final Stream<DynamicTest> nodeDeleteCanBeExecutedOnlyWithPrivilegedAccount()
                throws CertificateEncodingException {
            return hapiTest(
                    cryptoCreate("payer"),
                    cryptoCreate("batchOperator"),
                    nodeCreate("node100")
                            .description("desc")
                            .gossipCaCertificate(gossipCertificates.getFirst().getEncoded()),
                    // The inner txn is not signed by system account, so the transaction will fail
                    atomicBatch(nodeDelete("node100").payingWith("payer").batchKey("batchOperator"))
                            .payingWith("batchOperator")
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),
                    // Paying with a privileged account should succeed
                    atomicBatch(nodeDelete("node100").payingWith(DEFAULT_PAYER).batchKey("batchOperator"))
                            .payingWith("batchOperator"));
        }

        @HapiTest
        @DisplayName("Node update inside of a batch can be executed only with privileged account")
        final Stream<DynamicTest> nodeUpdateCanBeExecutedOnlyWithPrivilegedAccount()
                throws CertificateEncodingException {
            return hapiTest(
                    newKeyNamed("adminKey"),
                    cryptoCreate("payer"),
                    cryptoCreate("batchOperator"),
                    nodeCreate("node100")
                            .adminKey("adminKey")
                            .description("desc")
                            .gossipCaCertificate(gossipCertificates.getFirst().getEncoded()),
                    // The inner txn is not signed by system account, so the transaction will fail
                    atomicBatch(nodeUpdate("node100").payingWith("payer").batchKey("batchOperator"))
                            .payingWith("batchOperator")
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),
                    // Paying with a privileged account should succeed
                    atomicBatch(nodeUpdate("node100")
                                    .payingWith(DEFAULT_PAYER)
                                    .signedByPayerAnd("adminKey")
                                    .batchKey("batchOperator"))
                            .payingWith("batchOperator"));
        }
    }

    @HapiTest
    @DisplayName("Submit message to a private no submit key")
    final Stream<DynamicTest> submitMessageToPrivateNoSubmitKey() {
        final var collector = "collector";
        final var fee = fixedConsensusHbarFee(ONE_HBAR, collector);
        final var feeLimit = maxCustomFee("submitter", hbarLimit(1));
        return hapiTest(
                newKeyNamed("key"),
                newKeyNamed("submitKey"),
                cryptoCreate("batchOperator"),
                cryptoCreate(collector).balance(ONE_HBAR),
                cryptoCreate("submitter").balance(ONE_HUNDRED_HBARS).key("key"),
                createTopic("topic")
                        .feeExemptKeys("key")
                        .submitKeyName("submitKey")
                        .withConsensusCustomFee(fee),
                atomicBatch(submitMessageTo("topic")
                                .batchKey("batchOperator")
                                .maxCustomFee(feeLimit)
                                .message("TEST")
                                .payingWith("submitter")
                                // not signing with the submit key, so the transaction will fail
                                .signedBy("submitter"))
                        .payingWith("batchOperator")
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }
}
