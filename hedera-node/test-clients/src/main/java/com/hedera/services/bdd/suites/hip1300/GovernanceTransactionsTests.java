// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1300;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.ONLY_SUBPROCESS;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.noOp;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.SYSTEM_ADMIN;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSACTION_OVERSIZE;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hedera.services.bdd.suites.regression.system.LifecycleTest;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;

/**
 * A class with Governance Transactions tests.
 */
@Tag(ONLY_SUBPROCESS)
@Tag(MATS)
@HapiTestLifecycle
@OrderedInIsolation
@DisplayName("Governance Transactions Tests")
public class GovernanceTransactionsTests implements LifecycleTest {
    private static final int OVERSIZED_TXN_SIZE = 130 * 1024; // ~130KB
    private static final int LARGE_TXN_SIZE = 90 * 1024; // ~90KB
    private static final byte[] randomMemoBytes = TxnUtils.randomUtf8BytesNoZeroBytes(LARGE_TXN_SIZE);

    private static final String PAYER = "payer";
    private static final String RECEIVER = "receiver";
    private static final String TOPIC = "topic";
    private static final String TOPIC2 = "topic2";
    private static final String SUBMIT_KEY = "submit_key";
    private static final String SUBMIT_KEY2 = "submit_key2";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(
                Map.of("hedera.transaction.maxMemoUtf8Bytes", OVERSIZED_TXN_SIZE + "")); // to avoid memo size limit
    }

    // --- Tests to examine the behavior when the feature flag is turned on ---

    @HapiTest
    @Order(0)
    @DisplayName("Normal account still cannot submit more than 6KB transactions when the feature is enabled")
    public Stream<DynamicTest> nonGovernanceAccountCannotSubmitLargeSizeTransactionsIfEnabled() {
        final var largeSizeMemo = new String(randomMemoBytes, StandardCharsets.UTF_8);
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER).balance(0L),
                newKeyNamed(SUBMIT_KEY),
                createTopic(TOPIC)
                        .submitKeyName(SUBMIT_KEY)
                        .payingWith(PAYER)
                        .memo(largeSizeMemo)
                        .hasPrecheck(TRANSACTION_OVERSIZE)
                        // the submitted transaction exceeds 6144 bytes and will have its
                        // gRPC request terminated immediately
                        .orUnavailableStatus());
    }

    @HapiTest
    @Order(1)
    @DisplayName("Treasury and system admin accounts can submit more than 6KB transactions when the feature is enabled")
    public Stream<DynamicTest> governanceAccountCanSubmitLargeSizeTransactions() {
        final var largeSizeMemo = new String(randomMemoBytes, StandardCharsets.UTF_8);
        return hapiTest(
                cryptoCreate(RECEIVER).balance(0L),
                newKeyNamed(SUBMIT_KEY),
                newKeyNamed(SUBMIT_KEY2),
                createTopic(TOPIC)
                        .submitKeyName(SUBMIT_KEY)
                        .payingWith(GENESIS)
                        .memo(largeSizeMemo)
                        .hasKnownStatus(SUCCESS),
                createTopic(TOPIC2)
                        .submitKeyName(SUBMIT_KEY2)
                        .payingWith(SYSTEM_ADMIN)
                        .memo(largeSizeMemo)
                        .hasKnownStatus(SUCCESS));
    }

    @HapiTest
    @Order(2)
    @DisplayName("Non-governance account cannot submit transfers larger than 6KB even when the feature is enabled")
    public Stream<DynamicTest> nonGovernanceAccountTransactionLargerThan6kb() {
        final var largeSizeMemo = new String(randomMemoBytes, StandardCharsets.UTF_8);
        return hapiTest(
                cryptoCreate("payer").balance(ONE_MILLION_HBARS),
                cryptoCreate("receiver"),
                cryptoTransfer(tinyBarsFromTo("payer", "receiver", ONE_HUNDRED_HBARS))
                        .memo(largeSizeMemo)
                        .hasKnownStatus(TRANSACTION_OVERSIZE)
                        .orUnavailableStatus());
    }

    @HapiTest
    @Order(3)
    @DisplayName(
            "Treasury and system admin accounts cannot submit more than 6KB transactions when the feature is disabled at runtime")
    public Stream<DynamicTest> governanceAccountCannotSubmitLargeSizeTransactionsWhenDisabledDynamically() {
        final var largeSizeMemo = new String(randomMemoBytes, StandardCharsets.UTF_8);
        return hapiTest(
                cryptoCreate(RECEIVER).balance(0L),
                newKeyNamed(SUBMIT_KEY),
                newKeyNamed(SUBMIT_KEY2),
                overriding("governanceTransactions.isEnabled", "false"),
                createTopic(TOPIC)
                        .submitKeyName(SUBMIT_KEY)
                        .payingWith(GENESIS)
                        .memo(largeSizeMemo)
                        .hasKnownStatus(TRANSACTION_OVERSIZE),
                createTopic(TOPIC2)
                        .submitKeyName(SUBMIT_KEY2)
                        .payingWith(SYSTEM_ADMIN)
                        .memo(largeSizeMemo)
                        .hasKnownStatus(TRANSACTION_OVERSIZE));
    }

    // --- Disable the governance transactions feature and test nothing has changed in the previous behavior ---

    @HapiTest
    @Order(4)
    @DisplayName("Update the governance config to disable governance transactions")
    public Stream<DynamicTest> updateTheConfig() {
        return hapiTest(
                // The feature flag is only used once at startup (when building gRPC ServiceDefinitions),
                // so we can't toggle it via overriding(). Instead, we need to upgrade to the config version.
                prepareFakeUpgrade(),
                upgradeToNextConfigVersion(Map.of("governanceTransactions.isEnabled", "false"), noOp()));
    }

    @HapiTest
    @Order(5)
    @DisplayName("Normal account cannot submit more than 6KB transactions when the feature is disabled")
    public Stream<DynamicTest> nonGovernanceAccountCannotSubmitLargeSizeTransactions() {
        final var largeSizeMemo = new String(randomMemoBytes, StandardCharsets.UTF_8);
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER).balance(0L),
                newKeyNamed(SUBMIT_KEY),
                createTopic(TOPIC)
                        .submitKeyName(SUBMIT_KEY)
                        .payingWith(PAYER)
                        .memo(largeSizeMemo)
                        .hasPrecheck(TRANSACTION_OVERSIZE)
                        // the submitted transaction exceeds 6144 bytes and will have its
                        // gRPC request terminated immediately
                        .orUnavailableStatus());
    }

    @HapiTest
    @Order(6)
    @DisplayName(
            "Treasury and system admin accounts cannot submit more than 6KB transactions when the feature is disabled")
    public Stream<DynamicTest> governanceAccountsCannotSubmitLargeSizeTransactions() {
        final var largeSizeMemo = new String(randomMemoBytes, StandardCharsets.UTF_8);
        return hapiTest(
                cryptoCreate(RECEIVER).balance(0L),
                newKeyNamed(SUBMIT_KEY),
                newKeyNamed(SUBMIT_KEY2),
                createTopic(TOPIC)
                        .submitKeyName(SUBMIT_KEY)
                        .payingWith(GENESIS)
                        .memo(largeSizeMemo)
                        .hasPrecheck(TRANSACTION_OVERSIZE)
                        // the submitted transaction exceeds 6144 bytes and will have its
                        // gRPC request terminated immediately
                        .orUnavailableStatus(),
                createTopic(TOPIC2)
                        .submitKeyName(SUBMIT_KEY2)
                        .payingWith(SYSTEM_ADMIN)
                        .memo(largeSizeMemo)
                        .hasPrecheck(TRANSACTION_OVERSIZE)
                        // the submitted transaction exceeds 6144 bytes and will have its
                        // gRPC request terminated immediately
                        .orUnavailableStatus());
    }
}
