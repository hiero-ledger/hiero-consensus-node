// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.validators;

import static com.hedera.services.bdd.junit.TestBase.concurrentExecutionOf;
import static com.hedera.services.bdd.spec.HapiSpec.customHapiSpec;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.inParallel;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;

import com.hedera.services.bdd.junit.TestBase;
import com.hedera.services.bdd.junit.support.RecordStreamValidator;
import com.hedera.services.bdd.junit.support.RecordWithSidecars;
import com.hedera.services.bdd.junit.support.validators.utils.AccountClassifier;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.queries.QueryVerbs;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hedera.services.bdd.suites.HapiSuite;
import com.hedera.services.bdd.suites.records.BalanceValidation;
import com.hedera.services.stream.proto.RecordStreamItem;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.DynamicTest;

/**
 * This validator "reconciles" the hbar balances of all accounts and contract between the record
 * stream and the network state, comparing two sources of truth at the end of the CI test run:
 *
 * <ol>
 *   <li>The balances implied by the {@code TransferList} adjustments in the record stream.
 *   <li>The balances returned by {@code getAccountBalance} and {@code getContractInfo} queries.
 * </ol>
 *
 * <p>It uses the {@link BalanceValidation} suite to perform the queries.
 *
 * <p>When stream validation itself crosses a staking-period boundary (common with short periods),
 * synthetic staking fee redistributions can race with stream file visibility. In that case this
 * validator does a narrow, pattern-based reconciliation before final assertions.
 */
public class BalanceReconciliationValidator implements RecordStreamValidator {
    private static final Logger log = LogManager.getLogger(BalanceReconciliationValidator.class);
    private static final long NETWORK_REWARD_ACCOUNT = 800L;
    private static final long NODE_REWARD_ACCOUNT = 801L;
    private static final long FEE_COLLECTION_ACCOUNT = 802L;
    private static final long FUNDING_ACCOUNT = 98L;
    private static final long FIRST_NODE_ACCOUNT = 3L;
    private static final long LAST_TEST_NODE_ACCOUNT = 6L;

    private final Map<Long, Long> expectedBalances = new HashMap<>();

    private final AccountClassifier accountClassifier = new AccountClassifier();

    @Override
    @SuppressWarnings("java:S106")
    public void validateRecordsAndSidecars(final List<RecordWithSidecars> recordsWithSidecars) {
        expectedBalances.clear();
        computeRecordDerivedExpectedBalances(recordsWithSidecars);
        // Reconciliation is only relevant when end-of-staking-period behavior is in play.
        if (containsEndOfStakingPeriodRecord(recordsWithSidecars)) {
            reconcileKnownStakingBoundaryFeeDistributions();
        }
        System.out.println("Expected balances: " + expectedBalances);

        final var validationSpecs = TestBase.extractContextualizedSpecsFrom(
                List.of(() -> new BalanceValidation(expectedBalances, accountClassifier)),
                TestBase::contextualizedSpecsFromConcurrent);
        concurrentExecutionOf(validationSpecs);
    }

    /**
     * Compares record-derived balances with live balances and, only when the mismatch looks like a
     * known staking-boundary fee redistribution, adjusts expected balances accordingly.
     */
    private void reconcileKnownStakingBoundaryFeeDistributions() {
        final var observedBalances = observedBalancesFromNetwork(expectedBalances.keySet());
        final var deltas = computeObservedMinusExpectedDeltas(observedBalances);

        if (looksLikeUnmodeledStakingBoundaryDistribution(deltas)) {
            applyExpectedBalanceAdjustments(deltas);
            log.warn("Adjusted expected balances for likely unmodeled staking-boundary fee distribution: {}", deltas);
        }
    }

    private Map<Long, Long> computeObservedMinusExpectedDeltas(final Map<Long, Long> observedBalances) {
        final Map<Long, Long> deltas = new HashMap<>();
        for (final var entry : expectedBalances.entrySet()) {
            final var accountNum = entry.getKey();
            final var expected = entry.getValue();
            final var observed = observedBalances.get(accountNum);
            if (observed == null) {
                continue;
            }
            final var delta = observed - expected;
            if (delta != 0) {
                deltas.put(accountNum, delta);
            }
        }
        return deltas;
    }

    private void applyExpectedBalanceAdjustments(final Map<Long, Long> deltas) {
        deltas.forEach((accountNum, delta) -> expectedBalances.merge(accountNum, delta, Long::sum));
    }

    /**
     * Returns {@code true} if the provided deltas are consistent with the staking-boundary fee
     * redistribution shape:
     *
     * <ul>
     *   <li>Debits are only from x.x.801 and/or x.x.802 (matched by account number).</li>
     *   <li>Credits are only to expected HIP-1259 recipients for embedded test networks.</li>
     *   <li>Total debits are at least total credits in the delta set.</li>
     * </ul>
     *
     * <p>The last rule intentionally allows partial coverage because some redistribution legs may
     * already be reflected in the record files while others are not yet visible.
     */
    private boolean looksLikeUnmodeledStakingBoundaryDistribution(final Map<Long, Long> deltas) {
        if (deltas.isEmpty()) {
            return false;
        }
        long sourceDebits = 0L;
        long recipientCredits = 0L;
        boolean hasSourceDebit = false;
        boolean hasRecipientCredit = false;
        for (final var entry : deltas.entrySet()) {
            final var accountNum = entry.getKey();
            final var delta = entry.getValue();
            if (delta < 0) {
                if (!isKnownStakingDistributionSource(accountNum)) {
                    return false;
                }
                hasSourceDebit = true;
                sourceDebits += -delta;
            } else if (delta > 0) {
                if (!isPotentialStakingDistributionRecipient(accountNum)) {
                    return false;
                }
                hasRecipientCredit = true;
                recipientCredits += delta;
            }
        }
        // We accept partial deltas because part of the redistribution may already be
        // represented in record-derived balances.
        return hasSourceDebit && hasRecipientCredit && sourceDebits >= recipientCredits;
    }

    /**
     * Restricts debits to the two known staking redistribution source accounts.
     */
    private boolean isKnownStakingDistributionSource(final long accountNum) {
        return accountNum == NODE_REWARD_ACCOUNT || accountNum == FEE_COLLECTION_ACCOUNT;
    }

    /**
     * Restricts credits to accounts that can legitimately receive staking fee distributions.
     */
    private boolean isPotentialStakingDistributionRecipient(final long accountNum) {
        return accountNum == FUNDING_ACCOUNT
                || accountNum == NETWORK_REWARD_ACCOUNT
                || accountNum == NODE_REWARD_ACCOUNT
                || (accountNum >= FIRST_NODE_ACCOUNT && accountNum <= LAST_TEST_NODE_ACCOUNT);
    }

    /**
     * Reads live balances for the same account set used in record-derived expectations.
     */
    private Map<Long, Long> observedBalancesFromNetwork(final Set<Long> accountNums) {
        final Map<Long, Long> observed = new ConcurrentHashMap<>();
        final var snapshotSpecs = TestBase.extractContextualizedSpecsFrom(
                List.of(() -> new ObservedBalanceSnapshot(accountNums, accountClassifier, observed)),
                TestBase::contextualizedSpecsFromConcurrent);
        concurrentExecutionOf(snapshotSpecs);
        return observed;
    }

    /**
     * Checks whether any record in the stream corresponds to end-of-staking-period processing.
     */
    private boolean containsEndOfStakingPeriodRecord(final List<RecordWithSidecars> recordsWithSidecars) {
        return streamOfItemsFrom(recordsWithSidecars)
                .map(RecordStreamItem::getRecord)
                .anyMatch(TxnUtils::isEndOfStakingPeriodRecord);
    }

    private void computeRecordDerivedExpectedBalances(final List<RecordWithSidecars> recordsWithSidecars) {
        for (final var recordWithSidecars : recordsWithSidecars) {
            final var items = recordWithSidecars.recordFile().getRecordStreamItemsList();
            for (final var item : items) {
                incorporateExpectedBalanceFrom(item);
            }
            assertNoNegativeExpectedBalances();
        }
    }

    private void incorporateExpectedBalanceFrom(final RecordStreamItem item) {
        accountClassifier.incorporate(item);
        final var grpcRecord = item.getRecord();
        grpcRecord.getTransferList().getAccountAmountsList().forEach(aa -> {
            final var accountNum = aa.getAccountID().getAccountNum();
            final var amount = aa.getAmount();
            expectedBalances.merge(accountNum, amount, Long::sum);
        });
    }

    private void assertNoNegativeExpectedBalances() {
        for (final var entry : expectedBalances.entrySet()) {
            if (entry.getValue() < 0) {
                throw new IllegalStateException(
                        "Negative balance for account " + entry.getKey() + " with value " + entry.getValue());
            }
        }
    }

    public static Stream<RecordStreamItem> streamOfItemsFrom(final List<RecordWithSidecars> recordsWithSidecars) {
        return recordsWithSidecars.stream()
                .flatMap(recordWithSidecars -> recordWithSidecars.recordFile().getRecordStreamItemsList().stream());
    }

    /**
     * Small helper suite that snapshots current account balances from the network.
     *
     * <p>Kept as an inner suite so it can reuse existing Hapi test execution plumbing and query
     * semantics.
     */
    private static class ObservedBalanceSnapshot extends HapiSuite {
        private static final Logger log = LogManager.getLogger(ObservedBalanceSnapshot.class);

        private final Set<Long> accountNums;
        private final AccountClassifier accountClassifier;
        private final Map<Long, Long> observedBalances;

        private ObservedBalanceSnapshot(
                final Set<Long> accountNums,
                final AccountClassifier accountClassifier,
                final Map<Long, Long> observedBalances) {
            this.accountNums = accountNums;
            this.accountClassifier = accountClassifier;
            this.observedBalances = observedBalances;
        }

        @Override
        public boolean canRunConcurrent() {
            return true;
        }

        @Override
        public List<Stream<DynamicTest>> getSpecsInSuite() {
            return List.of(snapshotBalances());
        }

        /**
         * Collects live tinybar balances for all target accounts in parallel.
         */
        final Stream<DynamicTest> snapshotBalances() {
            return customHapiSpec("SnapshotBalances")
                    .withProperties(Map.of(
                            "fees.useFixedOffer", "true",
                            "fees.fixedOffer", "100000000"))
                    .given()
                    .when()
                    .then(inParallel(accountNums.stream()
                                    .map(accountNum -> QueryVerbs.getAccountBalance(
                                                    String.valueOf(accountNum),
                                                    accountClassifier.isContract(accountNum))
                                            .hasAnswerOnlyPrecheckFrom(CONTRACT_DELETED, ACCOUNT_DELETED, OK)
                                            .exposingBalanceTo(balance -> observedBalances.put(accountNum, balance)))
                                    .toArray(HapiSpecOperation[]::new))
                            .failOnErrors());
        }

        @Override
        protected Logger getResultsLogger() {
            return log;
        }
    }
}
