// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip869;

import static com.hedera.node.app.hapi.utils.CommonPbjConverters.toPbj;
import static com.hedera.services.bdd.junit.ContextRequirement.THROTTLE_OVERRIDES;
import static com.hedera.services.bdd.junit.EmbeddedReason.MUST_SKIP_INGEST;
import static com.hedera.services.bdd.junit.EmbeddedReason.NEEDS_STATE_ACCESS;
import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.ONLY_SUBPROCESS;
import static com.hedera.services.bdd.junit.hedera.utils.AddressBookUtils.CLASSIC_FIRST_NODE_ACCOUNT_NUM;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.keys.TrieSigMapGenerator.uniqueWithFullPrefixesFor;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeUpdate;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.EmbeddedVerbs.viewNode;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsdWithin;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.hip869.NodeCreateTest.generateX509Certificates;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_NODE_ACCOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_NODE_ACCOUNT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.RECORD_NOT_FOUND;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.hedera.services.bdd.junit.EmbeddedHapiTest;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyEmbeddedHapiTest;
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
        return hapiTest(
                newKeyNamed("adminKey"),
                nodeCreate("testNode")
                        .adminKey("adminKey")
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded()),
                nodeUpdate("testNode").accountId("").hasPrecheck(INVALID_NODE_ACCOUNT_ID));
    }

    @HapiTest
    final Stream<DynamicTest> updateAliasAccountIdFail() throws CertificateEncodingException {
        return hapiTest(
                newKeyNamed("adminKey"),
                nodeCreate("testNode")
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
        return hapiTest(
                newKeyNamed("testKey"),
                newKeyNamed("randomAccount"),
                cryptoCreate("payer").balance(10_000_000_000L),
                nodeCreate("node100")
                        .adminKey("testKey")
                        .description(description)
                        .fee(ONE_HBAR)
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded()),
                // Submit to a different node so ingest check is skipped
                nodeUpdate("node100")
                        .setNode(5)
                        .payingWith("payer")
                        .accountId("1000")
                        .fee(ONE_HBAR)
                        .hasKnownStatus(INVALID_SIGNATURE)
                        .via("failedUpdate"),
                getTxnRecord("failedUpdate").logged(),
                // The fee is charged here because the payer is not privileged
                validateChargedUsdWithin("failedUpdate", 0.001, 3.0),
                nodeUpdate("node100")
                        .adminKey("testKey")
                        .accountId("1000")
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
    @Tag(MATS)
    final Stream<DynamicTest> updateAccountIdWork() throws CertificateEncodingException {
        return hapiTest(
                newKeyNamed("adminKey"),
                nodeCreate("testNode")
                        .adminKey("adminKey")
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded()),
                nodeUpdate("testNode").adminKey("adminKey").accountId("1000"),
                withOpContext((spec, log) -> allRunFor(
                        spec,
                        viewNode(
                                "testNode",
                                node -> assertEquals(
                                        1000, node.accountId().accountNum(), "Node accountId should be updated")))));
    }

    @Tag(ONLY_SUBPROCESS)
    @HapiTest
    final Stream<DynamicTest> updateAccountIdAndSubmitWithOld() {
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

    @EmbeddedHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> accountIdGetsUpdatedCorrectly() {
        final AtomicReference<AccountID> initialAccountId = new AtomicReference<>();
        final AtomicReference<AccountID> newAccountId = new AtomicReference<>();
        return hapiTest(
                overriding("nodes.updateAccountIdAllowed", "true"),
                newKeyNamed("adminKey"),
                cryptoCreate("initialNodeAccount").exposingCreatedIdTo(initialAccountId::set),
                cryptoCreate("newNodeAccount").exposingCreatedIdTo(newAccountId::set),
                sourcing(() -> {
                    try {
                        return nodeCreate("testNode")
                                .adminKey("adminKey")
                                .accountId(initialAccountId.get())
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
        final AccountID sentinelValue = AccountID.newBuilder()
                .setShardNum(0)
                .setRealmNum(0)
                .setAccountNum(0)
                .build();
        final AtomicReference<AccountID> initialNodeAccountId = new AtomicReference<>();
        final AtomicReference<AccountID> newAccountId = new AtomicReference<>();
        return hapiTest(
                newKeyNamed("adminKey"),
                cryptoCreate("initialNodeAccount").exposingCreatedIdTo(initialNodeAccountId::set),
                cryptoCreate("newAccount").exposingCreatedIdTo(newAccountId::set),
                sourcing(() -> {
                    try {
                        return nodeCreate("testNode")
                                .accountId(initialNodeAccountId.get())
                                .adminKey("adminKey")
                                .gossipCaCertificate(
                                        gossipCertificates.getFirst().getEncoded());
                    } catch (CertificateEncodingException e) {
                        throw new RuntimeException(e);
                    }
                }),
                // signed with correct sig fails if account is sentinel
                nodeUpdate("testNode")
                        .fullAccountId(sentinelValue)
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
                        return nodeCreate("testNode")
                                .accountId(initialNodeAccountId.get())
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
}
