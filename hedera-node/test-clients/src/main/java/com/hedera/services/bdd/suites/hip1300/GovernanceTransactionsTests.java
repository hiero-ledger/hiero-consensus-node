// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1300;

import static com.hedera.services.bdd.junit.TestTags.ONLY_SUBPROCESS;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.keys.KeyShape.listOf;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.SYSTEM_ADMIN;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSACTION_OVERSIZE;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.transactions.consensus.HapiTopicCreate;
import com.hedera.services.bdd.suites.regression.system.LifecycleTest;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * A class with Governance Transactions tests.
 */
@Tag(ONLY_SUBPROCESS)
@HapiTestLifecycle
@DisplayName("Governance Transactions Tests")
public class GovernanceTransactionsTests implements LifecycleTest {
    private static final String PAYER = "payer";
    private static final String PAYER2 = "payer2";
    private static final String PAYER_KEY = "payer_key";
    private static final String PAYER_KEY2 = "payer_key2";
    private static final String RECEIVER = "receiver";
    private static final String TOPIC = "topic";
    private static final String TOPIC2 = "topic2";
    private static final String SUBMIT_KEY = "submit_key";
    private static final String SUBMIT_KEY2 = "submit_key2";

    private static final int OVERSIZED_TXN_SIZE = 130 * 1024; // ~130KB
    private static final int LARGE_TXN_SIZE = 90 * 1024; // ~90KB
    private static final String LARGE_SIZE_MEMO = StringUtils.repeat("a", LARGE_TXN_SIZE);
    private static final KeyShape LARGE_SIZE_KEY = listOf(50);

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(
                Map.of("hedera.transaction.maxMemoUtf8Bytes", OVERSIZED_TXN_SIZE + "")); // to avoid memo size limit
    }

    // --- Tests to examine the behavior when the feature flag is on ---

    @HapiTest
    @DisplayName("Normal account cannot submit more than 6KB transactions, signed by a larger key")
    public Stream<DynamicTest> nonGovernanceAccountCannotSubmitLargerSizeWithLargeKeyIfEnabled() {
        return hapiTest(
                newKeyNamed(PAYER_KEY).shape(LARGE_SIZE_KEY),
                newKeyNamed(PAYER_KEY2).shape(LARGE_SIZE_KEY),
                newKeyNamed(SUBMIT_KEY),
                cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS).hasKnownStatus(SUCCESS),
                cryptoCreate(PAYER2).key(PAYER_KEY2).balance(ONE_HUNDRED_HBARS).hasKnownStatus(SUCCESS),
                createTopic(TOPIC)
                        .submitKeyName(SUBMIT_KEY)
                        .payingWith(PAYER)
                        .signedBy(PAYER, PAYER2)
                        .hasPrecheck(TRANSACTION_OVERSIZE));
    }

    @HapiTest
    @DisplayName("Governance account can submit more than 6KB transactions, signed by a larger key")
    public Stream<DynamicTest> governanceAccountCanSubmitLargerSizeWithLargeKeyIfEnabled() {
        return hapiTest(
                newKeyNamed(PAYER_KEY).shape(LARGE_SIZE_KEY),
                newKeyNamed(PAYER_KEY2).shape(LARGE_SIZE_KEY),
                newKeyNamed(SUBMIT_KEY),
                cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS).hasKnownStatus(SUCCESS),
                cryptoCreate(PAYER2).key(PAYER_KEY2).balance(ONE_HUNDRED_HBARS).hasKnownStatus(SUCCESS),
                createTopic(TOPIC)
                        .submitKeyName(SUBMIT_KEY)
                        .payingWith(SYSTEM_ADMIN)
                        .signedBy(SYSTEM_ADMIN, PAYER, PAYER2)
                        .hasKnownStatus(SUCCESS));
    }

    @HapiTest
    @DisplayName("Normal account still cannot submit more than 6KB transactions when the feature is enabled")
    public Stream<DynamicTest> nonGovernanceAccountCannotSubmitLargeSizeTransactionsIfEnabled() {
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                newKeyNamed(SUBMIT_KEY),
                createTopic(TOPIC)
                        .submitKeyName(SUBMIT_KEY)
                        .payingWith(PAYER)
                        .memo(LARGE_SIZE_MEMO)
                        .hasPrecheck(TRANSACTION_OVERSIZE));
    }

    @HapiTest
    @DisplayName("Governance account cannot submit more than 6KB batch transactions when only the atomic batch is paid")
    public Stream<DynamicTest> governanceAccountCannotSubmitWhenPaidOnlyBatchIfEnabled() {
        final HapiTopicCreate innerTxn = createTopic(TOPIC)
                .submitKeyName(SUBMIT_KEY)
                .payingWith(PAYER)
                .memo(LARGE_SIZE_MEMO)
                .batchKey(GENESIS);

        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                newKeyNamed(SUBMIT_KEY),
                atomicBatch(innerTxn.hasPrecheck(TRANSACTION_OVERSIZE))
                        .payingWith(GENESIS)
                        .hasPrecheck(TRANSACTION_OVERSIZE));
    }

    @HapiTest
    @DisplayName(
            "Governance account cannot submit more than 6KB batch transactions when only the inner transaction is paid")
    public Stream<DynamicTest> governanceAccountCannotSubmitWhenPaidOnlyInnerIfEnabled() {
        final HapiTopicCreate innerTxn = createTopic(TOPIC)
                .submitKeyName(SUBMIT_KEY)
                .payingWith(GENESIS)
                .memo(LARGE_SIZE_MEMO)
                .batchKey(PAYER);

        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                newKeyNamed(SUBMIT_KEY),
                atomicBatch(innerTxn.hasPrecheck(SUCCESS)).payingWith(PAYER).hasPrecheck(TRANSACTION_OVERSIZE));
    }

    @HapiTest
    @DisplayName(
            "Governance account can submit more than 6KB batch transactions when both the batch and the inner transactions are paid")
    public Stream<DynamicTest> governanceAccountCanSubmitWhenBothArePaidIfEnabled() {
        final HapiTopicCreate innerTxn = createTopic(TOPIC)
                .submitKeyName(SUBMIT_KEY)
                .payingWith(GENESIS)
                .memo(LARGE_SIZE_MEMO)
                .batchKey(GENESIS);

        return hapiTest(
                newKeyNamed(SUBMIT_KEY),
                atomicBatch(innerTxn.hasKnownStatus(SUCCESS))
                        .payingWith(GENESIS)
                        .hasKnownStatus(SUCCESS));
    }

    @HapiTest
    @DisplayName("Treasury and system admin accounts can submit more than 6KB transactions when the feature is enabled")
    public Stream<DynamicTest> governanceAccountCanSubmitLargeSizeTransactions() {
        return hapiTest(
                newKeyNamed(SUBMIT_KEY),
                newKeyNamed(SUBMIT_KEY2),
                createTopic(TOPIC)
                        .submitKeyName(SUBMIT_KEY)
                        .payingWith(GENESIS)
                        .memo(LARGE_SIZE_MEMO)
                        .hasKnownStatus(SUCCESS),
                createTopic(TOPIC2)
                        .submitKeyName(SUBMIT_KEY2)
                        .payingWith(SYSTEM_ADMIN)
                        .memo(LARGE_SIZE_MEMO)
                        .hasKnownStatus(SUCCESS));
    }

    @HapiTest
    @DisplayName("Non-governance account cannot submit transfers larger than 6KB even when the feature is enabled")
    public Stream<DynamicTest> nonGovernanceAccountTransactionLargerThan6kb() {
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_MILLION_HBARS),
                cryptoCreate(RECEIVER),
                cryptoTransfer(tinyBarsFromTo(PAYER, RECEIVER, ONE_HUNDRED_HBARS))
                        .memo(LARGE_SIZE_MEMO)
                        .payingWith(PAYER)
                        .hasPrecheck(TRANSACTION_OVERSIZE));
    }

    // --- Test the behavior if the feature is disabled and verify nothing has changed ---

    @LeakyHapiTest(overrides = {"governanceTransactions.isEnabled"})
    @DisplayName(
            "Treasury and system admin accounts cannot submit more than 6KB transactions when the feature is disabled at runtime")
    public Stream<DynamicTest> governanceAccountCannotSubmitLargeSizeTransactionsWhenDisabledDynamically() {
        return hapiTest(
                overriding("governanceTransactions.isEnabled", "false"),
                newKeyNamed(SUBMIT_KEY),
                newKeyNamed(SUBMIT_KEY2),
                createTopic(TOPIC)
                        .submitKeyName(SUBMIT_KEY)
                        .payingWith(GENESIS)
                        .memo(LARGE_SIZE_MEMO)
                        .hasPrecheck(TRANSACTION_OVERSIZE),
                createTopic(TOPIC2)
                        .submitKeyName(SUBMIT_KEY2)
                        .payingWith(SYSTEM_ADMIN)
                        .memo(LARGE_SIZE_MEMO)
                        .hasPrecheck(TRANSACTION_OVERSIZE));
    }

    @LeakyHapiTest(overrides = {"governanceTransactions.isEnabled"})
    @DisplayName("Governance account cannot submit more than 6KB transactions, signed by a larger key, if disabled")
    public Stream<DynamicTest> governanceAccountCannotSubmitLargerSizeWithLargeKeyIfDisabled() {
        return hapiTest(
                overriding("governanceTransactions.isEnabled", "false"),
                newKeyNamed(PAYER_KEY).shape(LARGE_SIZE_KEY),
                newKeyNamed(PAYER_KEY2).shape(LARGE_SIZE_KEY),
                newKeyNamed(SUBMIT_KEY),
                cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS).hasKnownStatus(SUCCESS),
                cryptoCreate(PAYER2).key(PAYER_KEY2).balance(ONE_HUNDRED_HBARS).hasKnownStatus(SUCCESS),
                createTopic(TOPIC)
                        .submitKeyName(SUBMIT_KEY)
                        .payingWith(SYSTEM_ADMIN)
                        .signedBy(SYSTEM_ADMIN, PAYER, PAYER2)
                        .hasPrecheck(TRANSACTION_OVERSIZE));
    }

    @LeakyHapiTest(overrides = {"governanceTransactions.isEnabled"})
    @DisplayName("Normal account cannot submit more than 6KB transactions when the feature is disabled")
    public Stream<DynamicTest> nonGovernanceAccountCannotSubmitLargeSizeTransactions() {
        return hapiTest(
                overriding("governanceTransactions.isEnabled", "false"),
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                newKeyNamed(SUBMIT_KEY),
                createTopic(TOPIC)
                        .submitKeyName(SUBMIT_KEY)
                        .payingWith(PAYER)
                        .memo(LARGE_SIZE_MEMO)
                        .hasPrecheck(TRANSACTION_OVERSIZE));
    }

    @LeakyHapiTest(overrides = {"governanceTransactions.isEnabled"})
    @DisplayName(
            "Governance account cannot submit more than 6KB batch transactions when only the batch transaction is paid and feature disabled")
    public Stream<DynamicTest> governanceAccountCannotSubmitWhenPaidOnlyBatchIfDisabled() {
        final HapiTopicCreate innerTxn = createTopic(TOPIC)
                .submitKeyName(SUBMIT_KEY)
                .payingWith(PAYER)
                .memo(LARGE_SIZE_MEMO)
                .batchKey(GENESIS);

        return hapiTest(
                overriding("governanceTransactions.isEnabled", "false"),
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                newKeyNamed(SUBMIT_KEY),
                atomicBatch(innerTxn.hasPrecheck(TRANSACTION_OVERSIZE))
                        .payingWith(GENESIS)
                        .hasPrecheck(TRANSACTION_OVERSIZE));
    }

    @LeakyHapiTest(overrides = {"governanceTransactions.isEnabled"})
    @DisplayName(
            "Governance account cannot submit more than 6KB batch transactions when only the inner transaction is paid and feature disabled")
    public Stream<DynamicTest> governanceAccountCannotSubmitWhenPaidOnlyInnerIfDisabled() {
        final HapiTopicCreate innerTxn = createTopic(TOPIC)
                .submitKeyName(SUBMIT_KEY)
                .payingWith(GENESIS)
                .memo(LARGE_SIZE_MEMO)
                .batchKey(PAYER);

        return hapiTest(
                overriding("governanceTransactions.isEnabled", "false"),
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                newKeyNamed(SUBMIT_KEY),
                atomicBatch(innerTxn.hasPrecheck(TRANSACTION_OVERSIZE))
                        .payingWith(PAYER)
                        .hasPrecheck(TRANSACTION_OVERSIZE));
    }

    @LeakyHapiTest(overrides = {"governanceTransactions.isEnabled"})
    @DisplayName(
            "Governance account cannot submit more than 6KB batch transactions when both the batch and the inner transactions are paid")
    public Stream<DynamicTest> governanceAccountCannotSubmitWhenBothArePaidIfDisabled() {
        final HapiTopicCreate innerTxn = createTopic(TOPIC)
                .submitKeyName(SUBMIT_KEY)
                .payingWith(SYSTEM_ADMIN)
                .memo(LARGE_SIZE_MEMO)
                .batchKey(SYSTEM_ADMIN);

        return hapiTest(
                overriding("governanceTransactions.isEnabled", "false"),
                newKeyNamed(SUBMIT_KEY),
                atomicBatch(innerTxn.hasPrecheck(TRANSACTION_OVERSIZE))
                        .payingWith(SYSTEM_ADMIN)
                        .hasPrecheck(TRANSACTION_OVERSIZE));
    }

    @LeakyHapiTest(overrides = {"governanceTransactions.isEnabled"})
    @DisplayName(
            "Treasury and system admin accounts cannot submit more than 6KB transactions when the feature is disabled")
    public Stream<DynamicTest> governanceAccountsCannotSubmitLargeSizeTransactions() {
        return hapiTest(
                overriding("governanceTransactions.isEnabled", "false"),
                newKeyNamed(SUBMIT_KEY),
                newKeyNamed(SUBMIT_KEY2),
                createTopic(TOPIC)
                        .submitKeyName(SUBMIT_KEY)
                        .payingWith(GENESIS)
                        .memo(LARGE_SIZE_MEMO)
                        .hasPrecheck(TRANSACTION_OVERSIZE),
                createTopic(TOPIC2)
                        .submitKeyName(SUBMIT_KEY2)
                        .payingWith(SYSTEM_ADMIN)
                        .memo(LARGE_SIZE_MEMO)
                        .hasPrecheck(TRANSACTION_OVERSIZE));
    }
}
