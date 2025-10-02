// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.queries.meta;

import static com.hedera.node.app.hapi.utils.CommonUtils.noThrowSha384HashOf;
import static com.hedera.services.bdd.spec.assertions.AssertUtils.rethrowSummaryError;
import static com.hedera.services.bdd.spec.queries.QueryUtils.answerCostHeader;
import static com.hedera.services.bdd.spec.queries.QueryUtils.answerHeader;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.asDebits;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.asId;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.asIdForKeyLookUp;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.asTokenId;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.isEndOfStakingPeriodRecord;
import static com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil.asHeadlongAddress;
import static com.hedera.services.bdd.spec.transactions.schedule.HapiScheduleCreate.correspondingScheduledTxnId;
import static com.hedera.services.bdd.suites.HapiSuite.HBAR_TOKEN_SENTINEL;
import static com.hedera.services.bdd.suites.crypto.CryptoTransferSuite.sdec;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.RECORD_NOT_FOUND;
import static com.hederahashgraph.api.proto.java.ResponseType.COST_ANSWER;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.esaulpaugh.headlong.abi.ABIJSON;
import com.google.common.base.MoreObjects;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.hedera.node.app.hapi.utils.fee.CryptoFeeBuilder;
import com.hedera.services.bdd.spec.HapiPropertySource;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.assertions.ErroringAsserts;
import com.hedera.services.bdd.spec.assertions.ErroringAssertsProvider;
import com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts;
import com.hedera.services.bdd.spec.queries.HapiQueryOp;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hederahashgraph.api.proto.java.AccountAmount;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.AssessedCustomFee;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.NftTransfer;
import com.hederahashgraph.api.proto.java.Query;
import com.hederahashgraph.api.proto.java.QueryHeader;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.ResponseType;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.TokenAssociation;
import com.hederahashgraph.api.proto.java.TokenID;
import com.hederahashgraph.api.proto.java.TokenTransferList;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionGetRecordQuery;
import com.hederahashgraph.api.proto.java.TransactionID;
import com.hederahashgraph.api.proto.java.TransactionRecord;
import com.hederahashgraph.api.proto.java.TransferList;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import java.util.stream.Collectors;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Assertions;

public class HapiGetTxnRecord extends HapiQueryOp<HapiGetTxnRecord> {
    private static final Logger LOG = LogManager.getLogger(HapiGetTxnRecord.class);

    private static final TransactionID defaultTxnId = TransactionID.getDefaultInstance();
    public static final int MAX_PSEUDORANDOM_BYTES_LENGTH = 48;

    private String txn;
    private boolean scheduled = false;
    private boolean hasScheduledTransactionId = false;
    private boolean assertNothing = false;
    private boolean useDefaultTxnId = false;
    private boolean requestDuplicates = false;
    private boolean requestChildRecords = false;
    private boolean includeStakingRecordsInCount = true;
    // This field should be merged with includeStakingRecordsInCount in the future
    private boolean countStakingRecords = false;
    private boolean shouldBeTransferFree = false;
    private boolean assertOnlyPriority = false;
    private boolean assertNothingAboutHashes = false;
    private boolean lookupScheduledFromRegistryId = false;
    private boolean omitPaymentHeaderOnCostAnswer = false;
    private boolean validateStakingFees = false;

    private boolean stakingFeeExempted = false;
    private final List<Pair<String, Long>> accountAmountsToValidate = new ArrayList<>();
    private final List<Triple<String, String, Long>> tokenAmountsToValidate = new ArrayList<>();
    private final List<AssessedNftTransfer> assessedNftTransfersToValidate = new ArrayList<>();
    private final List<Triple<String, String, Long>> assessedCustomFeesToValidate = new ArrayList<>();
    private final List<Pair<String, String>> newTokenAssociations = new ArrayList<>();
    private OptionalInt assessedCustomFeesSize = OptionalInt.empty();
    private Optional<TransactionID> explicitTxnId = Optional.empty();
    private Optional<TransactionRecordAsserts> priorityExpectations = Optional.empty();
    private Optional<TransactionID> expectedParentId = Optional.empty();
    private Optional<Timestamp> expectedParentConsensusTime = Optional.empty();
    private Optional<List<TransactionRecordAsserts>> childRecordsExpectations = Optional.empty();
    private Optional<BiConsumer<TransactionRecord, Logger>> format = Optional.empty();
    private Optional<String> creationName = Optional.empty();
    private Optional<String> saveTxnRecordToRegistry = Optional.empty();
    private Optional<String> registryEntry = Optional.empty();
    private Optional<String> topicToValidate = Optional.empty();
    private Optional<byte[]> lastMessagedSubmitted = Optional.empty();
    private Optional<LongConsumer> priceConsumer = Optional.empty();
    private Optional<Map<AccountID, Long>> expectedDebits = Optional.empty();
    private Optional<Consumer<Map<AccountID, Long>>> debitsConsumer = Optional.empty();
    private Optional<ErroringAssertsProvider<List<TransactionRecord>>> duplicateExpectations = Optional.empty();
    private OptionalInt childRecordsCount = OptionalInt.empty();
    private Optional<Consumer<TransactionRecord>> observer = Optional.empty();

    @Nullable
    private Consumer<List<TransactionRecord>> allRecordsObserver = null;

    private boolean loggingOnlyFee = false;

    private Optional<Integer> pseudorandomNumberRange = Optional.empty();

    @Nullable
    private Consumer<List<String>> createdIdsObserver = null;

    @Nullable
    private Consumer<List<AccountCreationDetails>> creationDetailsObserver = null;

    @Nullable
    private Consumer<List<TokenID>> createdTokenIdsObserver = null;

    private boolean assertEffectivePayersAreKnown = false;

    private boolean pseudorandomBytesExpected = false;

    private boolean noPseudoRandomData = false;
    private List<Pair<String, Long>> paidStakingRewards = new ArrayList<>();
    private int numStakingRewardsPaid = -1;

    @Nullable
    private Consumer<List<AccountAmount>> stakingRewardsObserver = null;

    private Consumer<Object[]> eventDataObserver;
    private String eventName;
    private String contractResultAbi = null;

    public static ByteString sha384HashOf(final Transaction transaction) {
        if (transaction.getSignedTransactionBytes().isEmpty()) {
            return ByteString.copyFrom(noThrowSha384HashOf(transaction.toByteArray()));
        }

        return ByteString.copyFrom(
                noThrowSha384HashOf(transaction.getSignedTransactionBytes().toByteArray()));
    }

    private record ExpectedChildInfo(long pendingRewards) {}

    private final Map<Integer, ExpectedChildInfo> childExpectations = new HashMap<>();

    public HapiGetTxnRecord(final String txn) {
        setDefaultRetryValues();
        this.txn = txn;
    }

    public HapiGetTxnRecord(final TransactionID txnId) {
        setDefaultRetryValues();
        this.explicitTxnId = Optional.of(txnId);
    }

    /**
     * In the CI environment, some flaky tests have been observed failing with RECORD_NOT_FOUND.
     * This method implements a retry mechanism with a 2-second timeout to allow sufficient time
     * for the record to be prepared on the node before the operation is retried.
     * @see <a href="https://github.com/hiero-ledger/hiero-consensus-node/issues/18768">GitHub Issue #18768</a>
     * @see <a href="https://github.com/hiero-ledger/hiero-consensus-node/issues/18783">GitHub Issue #18783</a>
     */
    private void setDefaultRetryValues() {
        setRetryLimit(200); // 200 attempts * 10ms = 2s
        hasRetryAnswerOnlyPrecheck(RECORD_NOT_FOUND);
    }

    @Override
    public HederaFunctionality type() {
        return HederaFunctionality.TransactionGetRecord;
    }

    @Override
    protected HapiGetTxnRecord self() {
        return this;
    }

    public HapiGetTxnRecord exposingTo(final Consumer<TransactionRecord> observer) {
        this.observer = Optional.of(observer);
        return this;
    }

    public HapiGetTxnRecord exposingAllTo(final Consumer<List<TransactionRecord>> observer) {
        allRecordsObserver = observer;
        requestChildRecords = true;
        return this;
    }

    public HapiGetTxnRecord loggingOnlyFee() {
        loggingOnlyFee = true;
        return this;
    }

    public HapiGetTxnRecord exposingCreationsTo(final Consumer<List<String>> creationObserver) {
        this.createdIdsObserver = creationObserver;
        return andAllChildRecords();
    }

    public HapiGetTxnRecord exposingCreationDetailsTo(final Consumer<List<AccountCreationDetails>> observer) {
        creationDetailsObserver = observer;
        return andAllChildRecords();
    }

    public HapiGetTxnRecord exposingTokenCreationsTo(final Consumer<List<TokenID>> createdTokenIdsObserver) {
        this.createdTokenIdsObserver = createdTokenIdsObserver;
        return andAllChildRecords();
    }

    public HapiGetTxnRecord exposingFilteredCallResultVia(
            final String abi, final String eventName, final Consumer<Object[]> dataObserver) {
        this.contractResultAbi = abi;
        this.eventName = eventName;
        this.eventDataObserver = dataObserver;

        return this;
    }

    public HapiGetTxnRecord assertingKnownEffectivePayers() {
        this.assertEffectivePayersAreKnown = true;
        return this;
    }

    public HapiGetTxnRecord scheduled() {
        scheduled = true;
        return this;
    }

    public HapiGetTxnRecord hasScheduledTransactionId() {
        hasScheduledTransactionId = true;
        return this;
    }

    public HapiGetTxnRecord assertingOnlyPriority() {
        assertOnlyPriority = true;
        return this;
    }

    public HapiGetTxnRecord exposingStakingRewardsTo(final Consumer<List<AccountAmount>> observer) {
        stakingRewardsObserver = observer;
        return this;
    }

    public HapiGetTxnRecord omittingAnyPaymentForCostAnswer() {
        omitPaymentHeaderOnCostAnswer = true;
        return this;
    }

    public HapiGetTxnRecord countStakingRecords() {
        countStakingRecords = true;
        return this;
    }

    public HapiGetTxnRecord scheduledBy(final String creation) {
        scheduled = true;
        creationName = Optional.of(creation);
        lookupScheduledFromRegistryId = true;
        return this;
    }

    public HapiGetTxnRecord andAnyDuplicates() {
        requestDuplicates = true;
        return this;
    }

    public HapiGetTxnRecord andAllChildRecords() {
        requestChildRecords = true;
        return this;
    }

    public HapiGetTxnRecord assertingNothingAboutHashes() {
        assertNothingAboutHashes = true;
        return this;
    }

    /**
     * Specifies the expected number of child records for this transaction.
     * <p>
     * <b>Note:</b> This method returns all child records, which may include records from the end of the staking period.
     * If you do not specifically require staking period records, it is recommended to use
     * {@code hasNonStakingChildRecordCount()} for more consistent results.
     *
     * @param count the expected number of child records
     * @return this {@code HapiGetTxnRecord} instance for method chaining
     */
    public HapiGetTxnRecord hasChildRecordCount(final int count) {
        requestChildRecords = true;
        childRecordsCount = OptionalInt.of(count);
        return this;
    }

    public HapiGetTxnRecord hasNonStakingChildRecordCount(final int count) {
        requestChildRecords = true;
        includeStakingRecordsInCount = false;
        childRecordsCount = OptionalInt.of(count);
        return this;
    }

    public HapiGetTxnRecord hasPaidStakingRewards(final List<Pair<String, Long>> rewards) {
        paidStakingRewards = rewards;
        return this;
    }

    public HapiGetTxnRecord hasPaidStakingRewardsCount(final int n) {
        numStakingRewardsPaid = n;
        return this;
    }

    public HapiGetTxnRecord hasNoAliasInChildRecord(final int childIndex) {
        requestChildRecords = true;
        childExpectations.put(childIndex, new ExpectedChildInfo(0L));
        return this;
    }

    public HapiGetTxnRecord assertingNothing() {
        assertNothing = true;
        return this;
    }

    public HapiGetTxnRecord hasExactDebits(final Map<AccountID, Long> expected) {
        expectedDebits = Optional.of(expected);
        return this;
    }

    public HapiGetTxnRecord revealingDebitsTo(final Consumer<Map<AccountID, Long>> observer) {
        debitsConsumer = Optional.of(observer);
        return this;
    }

    public HapiGetTxnRecord providingFeeTo(final LongConsumer priceConsumer) {
        this.priceConsumer = Optional.of(priceConsumer);
        return this;
    }

    public HapiGetTxnRecord showsNoTransfers() {
        shouldBeTransferFree = true;
        return this;
    }

    public HapiGetTxnRecord saveCreatedContractListToRegistry(final String registryEntry) {
        this.registryEntry = Optional.of(registryEntry);
        return this;
    }

    public HapiGetTxnRecord saveTxnRecordToRegistry(final String txnRecordEntry) {
        this.saveTxnRecordToRegistry = Optional.of(txnRecordEntry);
        return this;
    }

    public HapiGetTxnRecord useDefaultTxnId() {
        useDefaultTxnId = true;
        return this;
    }

    public HapiGetTxnRecord hasPriority(final TransactionRecordAsserts provider) {
        priorityExpectations = Optional.of(provider);
        return this;
    }

    public HapiGetTxnRecord hasOnlyPseudoRandomBytes() {
        pseudorandomBytesExpected = true;
        pseudorandomNumberRange = Optional.empty();
        return this;
    }

    public HapiGetTxnRecord hasOnlyPseudoRandomNumberInRange(final int range) {
        pseudorandomBytesExpected = false;
        pseudorandomNumberRange = Optional.of(range);
        return this;
    }

    public HapiGetTxnRecord hasNoPseudoRandomData() {
        noPseudoRandomData = true;
        return this;
    }

    public HapiGetTxnRecord hasChildRecords(final TransactionRecordAsserts... providers) {
        requestChildRecords = true;
        childRecordsExpectations = Optional.of(Arrays.asList(providers));
        return this;
    }

    public HapiGetTxnRecord hasChildRecords(final TransactionID parentId, final TransactionRecordAsserts... providers) {
        expectedParentId = Optional.of(parentId);
        childRecordsExpectations = Optional.of(Arrays.asList(providers));
        return this;
    }

    public HapiGetTxnRecord hasParentConsensusTime(final Timestamp parentConsensusTime) {
        expectedParentConsensusTime = Optional.of(parentConsensusTime);
        return this;
    }

    public HapiGetTxnRecord hasDuplicates(final ErroringAssertsProvider<List<TransactionRecord>> provider) {
        duplicateExpectations = Optional.of(provider);
        return this;
    }

    public HapiGetTxnRecord hasCorrectRunningHash(final String topic, final byte[] lastMessage) {
        topicToValidate = Optional.of(topic);
        lastMessagedSubmitted = Optional.of(lastMessage);
        return this;
    }

    public HapiGetTxnRecord hasCorrectRunningHash(final String topic, final String lastMessage) {
        hasCorrectRunningHash(topic, lastMessage.getBytes());
        return this;
    }

    public HapiGetTxnRecord loggedWith(final BiConsumer<TransactionRecord, Logger> customFormat) {
        super.logged();
        format = Optional.of(customFormat);
        return this;
    }

    public HapiGetTxnRecord hasNewTokenAssociation(final String token, final String account) {
        newTokenAssociations.add(Pair.of(token, account));
        return this;
    }

    public HapiGetTxnRecord hasHbarAmount(final String account, final long amount) {
        accountAmountsToValidate.add(Pair.of(account, amount));
        return this;
    }

    public HapiGetTxnRecord hasTokenAmount(final String token, final String account, final long amount) {
        tokenAmountsToValidate.add(Triple.of(token, account, amount));
        return this;
    }

    public HapiGetTxnRecord hasNftTransfer(
            final String token, final String sender, final String receiver, final long serial) {
        assessedNftTransfersToValidate.add(new AssessedNftTransfer(token, sender, receiver, serial));
        return this;
    }

    public HapiGetTxnRecord hasAssessedCustomFee(final String token, final String account, final long amount) {
        assessedCustomFeesToValidate.add(Triple.of(token, account, amount));
        return this;
    }

    public HapiGetTxnRecord hasAssessedCustomFeesSize(final int size) {
        assessedCustomFeesSize = OptionalInt.of(size);
        return this;
    }

    public TransactionRecord getResponseRecord() {
        return response.getTransactionGetRecord().getTransactionRecord();
    }

    public TransactionRecord getChildRecord(final int i) {
        return response.getTransactionGetRecord().getChildTransactionRecords(i);
    }

    public TransactionRecord getFirstNonStakingChildRecord() {
        return nonStakingRecordsFrom(getChildRecords()).stream().findFirst().orElseThrow();
    }

    /**
     * Get all child records except for the staking records.
     * @param records the list of records
     * @return the list of non-staking records
     */
    public static List<TransactionRecord> nonStakingRecordsFrom(List<TransactionRecord> records) {
        return records.stream()
                .filter(record -> !isEndOfStakingPeriodRecord(record))
                .toList();
    }

    public List<TransactionRecord> getChildRecords() {
        return response.getTransactionGetRecord().getChildTransactionRecordsList();
    }

    public HapiGetTxnRecord hasStakingFeesPaid() {
        validateStakingFees = true;
        return this;
    }

    public HapiGetTxnRecord stakingFeeExempted() {
        stakingFeeExempted = true;
        return this;
    }

    @SuppressWarnings("java:S5960")
    private void assertPriority(final HapiSpec spec, final TransactionRecord actualRecord) throws Throwable {
        if (priorityExpectations.isPresent()) {
            final ErroringAsserts<TransactionRecord> asserts =
                    priorityExpectations.get().assertsFor(spec);
            final List<Throwable> errors = asserts.errorsIn(actualRecord);
            rethrowSummaryError(LOG, "Bad priority record!", errors);
        }
        expectedParentConsensusTime.ifPresent(
                timestamp -> assertEquals(timestamp, actualRecord.getParentConsensusTimestamp()));
        expectedDebits.ifPresent(debits -> assertEquals(debits, asDebits(actualRecord.getTransferList())));
    }

    private void assertChildRecords(final HapiSpec spec, final List<TransactionRecord> actualRecords) throws Throwable {
        if (childRecordsExpectations.isPresent()) {
            int numStakingRecords = 0;
            if (!actualRecords.isEmpty() && isEndOfStakingPeriodRecord(actualRecords.get(0))) {
                numStakingRecords++;
            }
            final var expectedChildRecords = childRecordsExpectations.get();
            // In case of inner batch transactions,
            // child transactions share single nonce counter (the one of the parent batch).
            // So we cant expect sequential IDs for child transactions. Instead, unique IDs are required.
            if (expectedParentId.isPresent()) {
                long uniqueNoncesCount = actualRecords.stream()
                        .map(record -> record.getTransactionID().getNonce())
                        .distinct()
                        .count();
                assertEquals(
                        actualRecords.size(),
                        uniqueNoncesCount,
                        String.format(
                                "Mismatch number of unique txn id nonces - %d and number of children - %d!",
                                uniqueNoncesCount, actualRecords.size()));
            }

            final var numActualRecords = actualRecords.size();
            var numRecordsToSubtract = numStakingRecords;
            if (countStakingRecords) {
                numRecordsToSubtract = 0;
            }
            if (expectedChildRecords.size() != (numActualRecords - numRecordsToSubtract)) {
                final var printableActualRecords = actualRecords.stream()
                        .filter(r -> !isEndOfStakingPeriodRecord(r))
                        .map(TransactionRecord::toString)
                        .collect(Collectors.joining(", "));
                assertEquals(
                        expectedChildRecords.size(),
                        numActualRecords - numRecordsToSubtract,
                        "Wrong # of (non-staking) child records, got: " + printableActualRecords);
            }
            for (int i = numRecordsToSubtract; i < numActualRecords; i++) {
                final var expectedChildRecord = expectedChildRecords.get(i - numRecordsToSubtract);
                final var actualChildRecord = actualRecords.get(i);
                final ErroringAsserts<TransactionRecord> asserts = expectedChildRecord.assertsFor(spec);
                final List<Throwable> errors = asserts.errorsIn(actualChildRecord);
                rethrowSummaryError(LOG, "Bad child records!", errors);
                expectedDebits.ifPresent(debits -> assertEquals(debits, asDebits(actualChildRecord.getTransferList())));
            }
        }
    }

    private void assertDuplicates(final HapiSpec spec) throws Throwable {
        if (duplicateExpectations.isPresent()) {
            final var asserts = duplicateExpectations.get().assertsFor(spec);
            final var errors =
                    asserts.errorsIn(response.getTransactionGetRecord().getDuplicateTransactionRecordsList());
            rethrowSummaryError(LOG, "Bad duplicate records!", errors);
        }
    }

    private void assertTransactionHash(final HapiSpec spec, final TransactionRecord actualRecord)
            throws InvalidProtocolBufferException {
        final Transaction transaction = Transaction.parseFrom(spec.registry().getBytes(txn));
        assertArrayEquals(
                sha384HashOf(transaction).toByteArray(),
                actualRecord.getTransactionHash().toByteArray(),
                "Bad transaction hash!");
    }

    private void assertTopicRunningHash(final HapiSpec spec, final TransactionRecord actualRecord) throws IOException {
        if (topicToValidate.isPresent()) {
            if (actualRecord.getReceipt().getStatus().equals(ResponseCodeEnum.SUCCESS)) {
                final var previousRunningHash = spec.registry().getBytes(topicToValidate.get());
                final var payer = actualRecord.getTransactionID().getAccountID();
                final var topicId = TxnUtils.asTopicId(topicToValidate.get(), spec);
                final var boas = new ByteArrayOutputStream();
                try (final var out = new ObjectOutputStream(boas)) {
                    out.writeObject(previousRunningHash);
                    out.writeLong(spec.setup().defaultTopicRunningHashVersion());
                    out.writeLong(payer.getShardNum());
                    out.writeLong(payer.getRealmNum());
                    out.writeLong(payer.getAccountNum());
                    out.writeLong(topicId.getShardNum());
                    out.writeLong(topicId.getRealmNum());
                    out.writeLong(topicId.getTopicNum());
                    out.writeLong(actualRecord.getConsensusTimestamp().getSeconds());
                    out.writeInt(actualRecord.getConsensusTimestamp().getNanos());
                    out.writeLong(actualRecord.getReceipt().getTopicSequenceNumber());
                    out.writeObject(noThrowSha384HashOf(lastMessagedSubmitted.get()));
                    out.flush();
                    final var expectedRunningHash = noThrowSha384HashOf(boas.toByteArray());
                    final var actualRunningHash = actualRecord.getReceipt().getTopicRunningHash();
                    assertArrayEquals(expectedRunningHash, actualRunningHash.toByteArray(), "Bad running hash!");
                    spec.registry().saveBytes(topicToValidate.get(), actualRunningHash);
                }
            } else {
                if (verboseLoggingOn) {
                    LOG.warn("Cannot validate running hash for an unsuccessful submit message" + " transaction!");
                }
            }
        }
    }

    @Override
    protected void assertExpectationsGiven(final HapiSpec spec) throws Throwable {
        if (assertNothing) {
            return;
        }

        final var txRecord = response.getTransactionGetRecord();
        final var actualRecord = txRecord.getTransactionRecord();
        assertCorrectRecord(spec, actualRecord);

        final var childRecords = txRecord.getChildTransactionRecordsList();
        assertChildRecords(spec, childRecords);

        if (assertEffectivePayersAreKnown) {
            actualRecord.getAssessedCustomFeesList().forEach(acf -> acf.getEffectivePayerAccountIdList()
                    .forEach(effPayer -> assertNotEquals(
                            0L,
                            effPayer.getAccountNum(),
                            "Assessed fee " + acf + " has unknown" + " effective payer")));
        }
    }

    private void assertCorrectRecord(final HapiSpec spec, final TransactionRecord actualRecord) throws Throwable {
        assertPriority(spec, actualRecord);
        if (scheduled || assertOnlyPriority) {
            return;
        }
        assertDuplicates(spec);
        if (!assertNothingAboutHashes) {
            assertTransactionHash(spec, actualRecord);
            assertTopicRunningHash(spec, actualRecord);
        }
        if (shouldBeTransferFree) {
            assertEquals(0, actualRecord.getTokenTransferListsCount(), "Unexpected transfer list!");
        }
        if (!accountAmountsToValidate.isEmpty()) {
            final var accountAmounts = actualRecord.getTransferList().getAccountAmountsList();
            accountAmountsToValidate.forEach(pair ->
                    validateAccountAmount(asIdForKeyLookUp(pair.getLeft(), spec), pair.getRight(), accountAmounts));
        }
        final var tokenTransferLists = actualRecord.getTokenTransferListsList();
        if (!tokenAmountsToValidate.isEmpty()) {
            tokenAmountsToValidate.forEach(triple -> validateTokenAmount(
                    asTokenId(triple.getLeft(), spec),
                    asIdForKeyLookUp(triple.getMiddle(), spec),
                    triple.getRight(),
                    tokenTransferLists));
        }
        if (!assessedNftTransfersToValidate.isEmpty()) {
            assessedNftTransfersToValidate.forEach(transfer -> validateAssessedNftTransfer(
                    asTokenId(transfer.getToken(), spec),
                    asIdForKeyLookUp(transfer.getSender(), spec),
                    asIdForKeyLookUp(transfer.getReceiver(), spec),
                    transfer.getSerial(),
                    tokenTransferLists));
        }
        final var actualAssessedCustomFees = actualRecord.getAssessedCustomFeesList();
        if (assessedCustomFeesSize.isPresent()) {
            assertEquals(
                    assessedCustomFeesSize.getAsInt(),
                    actualAssessedCustomFees.size(),
                    "Unexpected size of assessed_custom_fees:\n" + actualAssessedCustomFees);
        }
        if (!assessedCustomFeesToValidate.isEmpty()) {
            assessedCustomFeesToValidate.forEach(triple -> validateAssessedCustomFees(
                    triple.getLeft().equals(HBAR_TOKEN_SENTINEL) ? null : asTokenId(triple.getLeft(), spec),
                    asId(triple.getMiddle(), spec),
                    triple.getRight(),
                    actualAssessedCustomFees));
        }
        final var actualNewTokenAssociations = actualRecord.getAutomaticTokenAssociationsList();
        if (!newTokenAssociations.isEmpty()) {
            newTokenAssociations.forEach(pair -> validateNewTokenAssociations(
                    asTokenId(pair.getLeft(), spec),
                    asIdForKeyLookUp(pair.getRight(), spec),
                    actualNewTokenAssociations));
        }
        if (!childExpectations.isEmpty()) {
            for (final var index : childExpectations.entrySet()) {
                final var childRecord = childRecords.get(index.getKey());
                assertEquals(ByteString.EMPTY, childRecord.getAlias());
            }
        }
        if (numStakingRewardsPaid != -1) {
            assertEquals(
                    numStakingRewardsPaid,
                    actualRecord.getPaidStakingRewardsCount(),
                    "Wrong # of staking rewards paid");
        }
        if (!paidStakingRewards.isEmpty()) {
            if (actualRecord.getPaidStakingRewardsList().isEmpty()) {
                Assertions.fail("PaidStakingRewards not present in the txnRecord");
            }

            for (int i = 0; i < paidStakingRewards.size(); i++) {
                final var expectedRewards = paidStakingRewards.get(i);
                final var actualPaidRewards = actualRecord.getPaidStakingRewards(i);
                validateAccountAmount(
                        asId(String.valueOf(expectedRewards.getLeft()), spec),
                        expectedRewards.getRight().longValue(),
                        List.of(actualPaidRewards));
            }
        }

        if (validateStakingFees) {
            final var actualTxnFee = actualRecord.getTransactionFee();
            final var transferList = actualRecord.getTransferList();
            final var pendingRewards = actualRecord.getPaidStakingRewardsList().stream()
                    .mapToLong(AccountAmount::getAmount)
                    .sum();
            assertStakingAccountFees(transferList, actualTxnFee, pendingRewards);
        }

        if (stakingFeeExempted) {
            final var transferList = actualRecord.getTransferList();
            assertNoStakingAccountFees(transferList);
        }
        if (noPseudoRandomData) {
            final var actualByteString = actualRecord.getPrngBytes();
            final var actualRandomNum = actualRecord.getPrngNumber();
            assertEquals(TransactionRecord.EntropyCase.ENTROPY_NOT_SET, actualRecord.getEntropyCase());
            assertEquals(ByteString.EMPTY, actualByteString);
            assertEquals(0, actualRandomNum);
        }
        if (pseudorandomBytesExpected) {
            final var actualByteString = actualRecord.getPrngBytes();
            final var actualRandomNum = actualRecord.getPrngNumber();
            assertEquals(MAX_PSEUDORANDOM_BYTES_LENGTH, actualByteString.size());
            assertEquals(0, actualRandomNum);
        }

        if (pseudorandomNumberRange.isPresent()) {
            final var actualByteString = actualRecord.getPrngBytes();
            final var actualRandomNum = actualRecord.getPrngNumber();
            assertTrue(actualByteString.isEmpty());
            assertTrue(actualRandomNum >= 0 && actualRandomNum < pseudorandomNumberRange.get());
        }
        if (hasScheduledTransactionId) {
            final var scheduledTransactionId = actualRecord.getReceipt().getScheduledTransactionID();
            assertNotNull(scheduledTransactionId);
        }
    }

    private void assertNoStakingAccountFees(final TransferList transferList) {
        for (final var adjust : transferList.getAccountAmountsList()) {
            final var id = adjust.getAccountID();
            if ((id.getAccountNum() == 801L || id.getAccountNum() == 800L) && adjust.getAmount() > 0) {
                Assertions.fail("Staking fees present in the txnRecord");
            }
        }
    }

    private void assertStakingAccountFees(
            final TransferList transferList, final long actualTxnFee, final long pendingRewards) {
        long amount = 0L;
        for (final var adjust : transferList.getAccountAmountsList()) {
            final var id = adjust.getAccountID();
            if (id.getAccountNum() == 3L || id.getAccountNum() == 98L) {
                amount += adjust.getAmount();
            }
        }
        final var stakingFee = actualTxnFee - amount;
        for (final var adjust : transferList.getAccountAmountsList()) {
            final var id = adjust.getAccountID();
            if (id.getAccountNum() == 800L) {
                assertEquals(stakingFee / 2 - pendingRewards, adjust.getAmount());
            }
            if (id.getAccountNum() == 801L) {
                assertEquals(stakingFee / 2, adjust.getAmount());
            }
        }
    }

    private void validateNewTokenAssociations(
            final TokenID token, final AccountID account, final List<TokenAssociation> newTokenAssociations) {
        for (final var newTokenAssociation : newTokenAssociations) {
            if (newTokenAssociation.getTokenId().equals(token)
                    && newTokenAssociation.getAccountId().equals(account)) {
                return;
            }
        }
        Assertions.fail(cannotFind(token, account) + " in the new_token_associations of the txnRecord");
    }

    private void validateAssessedCustomFees(
            final TokenID tokenID,
            final AccountID accountID,
            final long amount,
            final List<AssessedCustomFee> assessedCustomFees) {
        for (final var acf : assessedCustomFees) {
            if (acf.getAmount() == amount
                    && acf.getFeeCollectorAccountId().equals(accountID)
                    && (!acf.hasTokenId() || acf.getTokenId().equals(tokenID))) {
                return;
            }
        }

        Assertions.fail(cannotFind(tokenID, accountID, amount) + " in the assessed_custom_fees of the txnRecord");
    }

    private String cannotFind(final TokenID tokenID, final AccountID accountID) {
        return "Cannot find TokenID: " + tokenID + " AccountID: " + accountID;
    }

    private void validateTokenAmount(
            final TokenID tokenID,
            final AccountID accountID,
            final long amount,
            final List<TokenTransferList> tokenTransferLists) {
        for (final var ttl : tokenTransferLists) {
            if (ttl.getToken().equals(tokenID)) {
                final var accountAmounts = ttl.getTransfersList();
                if (!accountAmounts.isEmpty() && foundInAccountAmountsList(accountID, amount, accountAmounts)) {
                    return;
                }
            }
        }

        Assertions.fail(cannotFind(tokenID, accountID, amount) + " in the tokenTransferLists of the txnRecord");
    }

    private String cannotFind(final TokenID tokenID, final AccountID accountID, final long amount) {
        return cannotFind(tokenID, accountID) + " and amount: " + amount;
    }

    private void validateAssessedNftTransfer(
            final TokenID tokenID,
            final AccountID sender,
            final AccountID receiver,
            final long serial,
            final List<TokenTransferList> tokenTransferLists) {
        for (final var ttl : tokenTransferLists) {
            if (ttl.getToken().equals(tokenID)) {
                final var nftTransferList = ttl.getNftTransfersList();
                if (!nftTransferList.isEmpty() && foundInNftTransferList(sender, receiver, serial, nftTransferList)) {
                    return;
                }
            }
        }

        Assertions.fail(cannotFind(tokenID, sender, receiver, serial) + " in the tokenTransferLists of the txnRecord");
    }

    private String cannotFind(
            final TokenID tokenID, final AccountID sender, final AccountID receiver, final long serial) {
        return "Cannot find TokenID: "
                + tokenID
                + " sender: "
                + sender
                + " receiver: "
                + receiver
                + " and serial: "
                + serial;
    }

    private boolean foundInNftTransferList(
            final AccountID sender,
            final AccountID receiver,
            final long serial,
            final List<NftTransfer> nftTransferList) {
        for (final var nftTransfer : nftTransferList) {
            if (nftTransfer.getSerialNumber() == serial
                    && nftTransfer.getSenderAccountID().equals(sender)
                    && nftTransfer.getReceiverAccountID().equals(receiver)) {
                return true;
            }
        }

        return false;
    }

    private void validateAccountAmount(
            final AccountID accountID, final long amount, final List<AccountAmount> accountAmountsList) {
        final var found = foundInAccountAmountsList(accountID, amount, accountAmountsList);
        assertTrue(
                found,
                "Cannot find AccountID: " + accountID + " and amount: " + amount + " in :: " + accountAmountsList);
    }

    private boolean foundInAccountAmountsList(
            final AccountID accountID, final long amount, final List<AccountAmount> accountAmountsList) {
        for (final var aa : accountAmountsList) {
            if (aa.getAmount() == amount && aa.getAccountID().equals(accountID)) {
                return true;
            }
        }

        return false;
    }

    @Override
    @SuppressWarnings("java:S1874")
    protected void processAnswerOnlyResponse(@NonNull final HapiSpec spec) {
        final TransactionRecord rcd = response.getTransactionGetRecord().getTransactionRecord();
        if (contractResultAbi != null) {
            exposeRequestedEventsFrom(rcd);
        }
        if (stakingRewardsObserver != null) {
            stakingRewardsObserver.accept(rcd.getPaidStakingRewardsList());
        }
        observer.ifPresent(obs -> obs.accept(rcd));
        childRecords = response.getTransactionGetRecord().getChildTransactionRecordsList();
        childRecordsCount.ifPresent(count -> {
            if (includeStakingRecordsInCount) {
                assertEquals(count, childRecords.size());
            } else {
                int observedCount = childRecords.size();
                if (!childRecords.isEmpty() && isEndOfStakingPeriodRecord(childRecords.get(0))) {
                    observedCount--;
                }
                assertEquals(count, observedCount, "Wrong # of non-staking records");
            }
        });
        if (allRecordsObserver != null) {
            final List<TransactionRecord> allRecords = new ArrayList<>();
            allRecords.add(rcd);
            allRecords.addAll(childRecords);
            allRecordsObserver.accept(allRecords);
        }
        final List<String> creations = (createdIdsObserver != null) ? new ArrayList<>() : null;
        final List<AccountCreationDetails> creationDetails =
                (creationDetailsObserver != null) ? new ArrayList<>() : null;
        final List<TokenID> tokenCreations = (createdTokenIdsObserver != null) ? new ArrayList<>() : null;
        final var firstUserNum = spec.startupProperties().getLong("hedera.firstUserEntity");
        for (final var rec : childRecords) {
            if (rec.getReceipt().hasAccountID()
                    && rec.getReceipt().getAccountID().getAccountNum() >= firstUserNum) {
                if (creations != null) {
                    creations.add(
                            HapiPropertySource.asAccountString(rec.getReceipt().getAccountID()));
                }
                if (creationDetails != null) {
                    creationDetails.add(new AccountCreationDetails(
                            rec.getReceipt().getAccountID(),
                            asHeadlongAddress(rec.getEvmAddress().toByteArray())));
                }
            }
            if (rec.getReceipt().hasTokenID() && tokenCreations != null) {
                tokenCreations.add(rec.getReceipt().getTokenID());
            }
            if (!rec.getAlias().isEmpty()) {
                spec.registry()
                        .saveAccountId(
                                rec.getAlias().toStringUtf8(), rec.getReceipt().getAccountID());
                try {
                    spec.registry().saveKey(rec.getAlias().toStringUtf8(), Key.parseFrom(rec.getAlias()));
                } catch (InvalidProtocolBufferException e) {
                    LOG.debug(
                            "Cannot save alias {} as key, since it is an evmAddress alias.",
                            rec.getAlias()::toStringUtf8);
                }
                LOG.info(
                        "{}  Saving alias {} to registry for Account ID {}",
                        spec::logPrefix,
                        rec.getAlias()::toStringUtf8,
                        rec.getReceipt()::getAccountID);
            }
        }
        if (createdIdsObserver != null) {
            createdIdsObserver.accept(creations);
        }
        if (createdTokenIdsObserver != null) {
            createdTokenIdsObserver.accept(tokenCreations);
        }
        if (creationDetailsObserver != null) {
            creationDetailsObserver.accept(creationDetails);
        }

        if (loggingOnlyFee && spec.ratesProvider().hasRateSet()) {
            final var priceInUsd = sdec(spec.ratesProvider().toUsdWithActiveRates(rcd.getTransactionFee()), 5);
            LOG.info("{}Record of {} charged ${}", spec::logPrefix, () -> txn, () -> priceInUsd);
        }
        if (verboseLoggingOn) {
            if (format.isPresent()) {
                format.get().accept(rcd, LOG);
            } else {
                final var fee = rcd.getTransactionFee();
                final var rates = spec.ratesProvider();
                if (rates.hasRateSet()) {
                    final var priceInUsd = sdec(rates.toUsdWithActiveRates(fee), 5);
                    LOG.info("{}Record (charged ${}): {}", spec::logPrefix, () -> priceInUsd, () -> rcd);
                }
                LOG.info(
                        "{}  And {} child record{}: {}",
                        spec::logPrefix,
                        childRecords::size,
                        () -> childRecords.size() > 1 ? "s" : "",
                        () -> childRecords);
                LOG.info("Duplicates: {}", response.getTransactionGetRecord().getDuplicateTransactionRecordsList());
            }
        }
        if (response.getTransactionGetRecord().getHeader().getNodeTransactionPrecheckCode() == OK) {
            priceConsumer.ifPresent(pc -> pc.accept(rcd.getTransactionFee()));
            debitsConsumer.ifPresent(dc -> dc.accept(asDebits(rcd.getTransferList())));
        }
        if (registryEntry.isPresent()) {
            spec.registry()
                    .saveContractList(
                            registryEntry.get() + "CreateResult",
                            rcd.getContractCreateResult().getCreatedContractIDsList());
            spec.registry()
                    .saveContractList(
                            registryEntry.get() + "CallResult",
                            rcd.getContractCallResult().getCreatedContractIDsList());
        }
        if (saveTxnRecordToRegistry.isPresent()) {
            spec.registry().saveTransactionRecord(saveTxnRecordToRegistry.get(), rcd);
        }
    }

    private void exposeRequestedEventsFrom(final TransactionRecord rcd) {
        final var events = ABIJSON.parseEvents(contractResultAbi);
        final var event =
                events.stream().filter(e -> eventName.equals(e.getName())).findFirst();
        final var logs = rcd.getContractCallResult().getLogInfoList();
        for (final var log : logs) {
            final var data = log.getData().toByteArray();
            final var topics = new byte[log.getTopicCount()][];
            for (int i = 0, n = log.getTopicCount(); i < n; i++) {
                topics[i] = log.getTopic(i).toByteArray();
            }
            if (event.isPresent()) {
                final var decodedLog = event.get().decodeArgs(topics, data);
                eventDataObserver.accept(decodedLog.toArray());
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected Query queryFor(
            @NonNull final HapiSpec spec,
            @NonNull final Transaction payment,
            @NonNull final ResponseType responseType) {
        return getRecordQuery(spec, payment, responseType == ResponseType.COST_ANSWER);
    }

    private Query getRecordQuery(final HapiSpec spec, final Transaction payment, final boolean costOnly) {
        TransactionID txnId = useDefaultTxnId
                ? defaultTxnId
                : explicitTxnId.orElseGet(() -> spec.registry().getTxnId(txn));
        if (lookupScheduledFromRegistryId) {
            txnId = spec.registry().getTxnId(correspondingScheduledTxnId(creationName.get()));
        } else {
            if (scheduled) {
                txnId = txnId.toBuilder().setScheduled(true).build();
            }
        }
        final QueryHeader header;
        if (costOnly && omitPaymentHeaderOnCostAnswer) {
            header = QueryHeader.newBuilder().setResponseType(COST_ANSWER).build();
        } else {
            header = costOnly ? answerCostHeader(payment) : answerHeader(payment);
        }
        final TransactionGetRecordQuery getRecordQuery = TransactionGetRecordQuery.newBuilder()
                .setHeader(header)
                .setTransactionID(txnId)
                .setIncludeDuplicates(requestDuplicates)
                .setIncludeChildRecords(requestChildRecords)
                .build();
        return Query.newBuilder().setTransactionGetRecord(getRecordQuery).build();
    }

    @Override
    protected long costOnlyNodePayment(final HapiSpec spec) {
        return spec.fees()
                .forOp(
                        HederaFunctionality.TransactionGetRecord,
                        CryptoFeeBuilder.getCostTransactionRecordQueryFeeMatrices());
    }

    @Override
    protected boolean needsPayment() {
        return true;
    }

    @Override
    protected MoreObjects.ToStringHelper toStringHelper() {
        if (explicitTxnId.isPresent()) {
            return super.toStringHelper().add("explicitTxnId", true);
        } else {
            return super.toStringHelper().add("txn", txn);
        }
    }

    public class AssessedNftTransfer {
        private final String token;
        private final String sender;
        private final String receiver;
        private final long serial;

        public AssessedNftTransfer(final String token, final String sender, final String receiver, final long serial) {
            this.token = token;
            this.sender = sender;
            this.receiver = receiver;
            this.serial = serial;
        }

        public String getToken() {
            return token;
        }

        public String getSender() {
            return sender;
        }

        public String getReceiver() {
            return receiver;
        }

        public long getSerial() {
            return serial;
        }
    }
}
