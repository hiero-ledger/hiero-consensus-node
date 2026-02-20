// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip869;

import static com.hedera.services.bdd.junit.ContextRequirement.THROTTLE_OVERRIDES;
import static com.hedera.services.bdd.junit.EmbeddedReason.MUST_SKIP_INGEST;
import static com.hedera.services.bdd.junit.EmbeddedReason.NEEDS_STATE_ACCESS;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.keys.TrieSigMapGenerator.uniqueWithFullPrefixesFor;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeUpdate;
import static com.hedera.services.bdd.spec.utilops.EmbeddedVerbs.viewNode;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.safeValidateChargedUsdWithin;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsdWithin;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.NODE_UPDATE_BASE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.SIGNATURE_FEE_AFTER_MULTIPLIER;
import static com.hedera.services.bdd.suites.hip869.NodeCreateTest.generateX509Certificates;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_NODE_ACCOUNT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
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

/**
 * Tests expected behavior when the {@code nodes.updateAccountIdAllowed} feature flag is on for
 * <a href="https://hips.hedera.com/hip/hip-869">HIP-869, "Dynamic Address Book - Stage 1 - HAPI Endpoints"</a>.
 */
// nodes.updateAccountIdAllowed is true by default so it is safe to run this concurrently
// @Tag(SERIAL)
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
                safeValidateChargedUsdWithin("failedUpdate", 0.001, 1.0, NODE_UPDATE_BASE_FEE_USD, 1.0),
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
                safeValidateChargedUsdWithin(
                        "failedUpdateMultipleSigs",
                        0.0011276316,
                        3.0,
                        NODE_UPDATE_BASE_FEE_USD + 2 * SIGNATURE_FEE_AFTER_MULTIPLIER,
                        1.0));
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
}
