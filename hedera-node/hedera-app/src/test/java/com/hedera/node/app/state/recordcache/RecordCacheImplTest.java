// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.state.recordcache;

import static com.hedera.hapi.node.base.ResponseCodeEnum.ACCOUNT_IS_IMMUTABLE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.DUPLICATE_TRANSACTION;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_NODE_ACCOUNT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.OK;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.UNKNOWN;
import static com.hedera.node.app.state.HederaRecordCache.DuplicateCheckResult.NO_DUPLICATE;
import static com.hedera.node.app.state.HederaRecordCache.DuplicateCheckResult.OTHER_NODE;
import static com.hedera.node.app.state.HederaRecordCache.DuplicateCheckResult.SAME_NODE;
import static com.hedera.node.app.state.recordcache.schemas.V0490RecordCacheSchema.TRANSACTION_RECEIPTS_STATE_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.state.recordcache.TransactionReceiptEntries;
import com.hedera.hapi.node.state.recordcache.TransactionReceiptEntry;
import com.hedera.hapi.node.transaction.TransactionReceipt;
import com.hedera.hapi.node.transaction.TransactionRecord;
import com.hedera.node.app.fixtures.AppTestBase;
import com.hedera.node.app.fixtures.state.FakeSchemaRegistry;
import com.hedera.node.app.fixtures.state.FakeState;
import com.hedera.node.app.services.StartupNetworks;
import com.hedera.node.app.spi.info.NetworkInfo;
import com.hedera.node.app.spi.info.NodeInfo;
import com.hedera.node.app.spi.records.RecordCache;
import com.hedera.node.app.state.DeduplicationCache;
import com.hedera.node.app.state.HederaRecordCache.DueDiligenceFailure;
import com.hedera.node.app.state.WorkingStateAccessor;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfiguration;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.node.config.data.LedgerConfig;
import com.swirlds.state.spi.WritableQueueState;
import com.swirlds.state.test.fixtures.ListWritableQueueState;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.time.InstantSource;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
final class RecordCacheImplTest extends AppTestBase {
    private static final int MAX_QUERYABLE_PER_ACCOUNT = 10;
    private static final int RESPONSE_CODES_TO_TEST = 32;
    private static final TransactionReceipt UNHANDLED_RECEIPT =
            TransactionReceipt.newBuilder().status(UNKNOWN).build();
    private static final AccountID NODE_ACCOUNT_ID =
            AccountID.newBuilder().accountNum(3).build();
    private static final AccountID PAYER_ACCOUNT_ID =
            AccountID.newBuilder().accountNum(1001).build();

    private DeduplicationCache dedupeCache;

    @Mock
    WorkingStateAccessor wsa;

    private final InstantSource instantSource = InstantSource.system();

    @Mock
    private ConfigProvider props;

    @Mock
    private NetworkInfo networkInfo;

    @Mock
    private StartupNetworks startupNetworks;

    @Mock
    private NodeInfo nodeInfo;

    @BeforeEach
    void setUp(
            @Mock final VersionedConfiguration versionedConfig,
            @Mock final HederaConfig hederaConfig,
            @Mock final LedgerConfig ledgerConfig,
            @Mock final NetworkInfo networkInfo) {
        dedupeCache = new DeduplicationCacheImpl(props, instantSource);
        final var registry = new FakeSchemaRegistry();
        final var state = new FakeState();
        final var svc = new RecordCacheService();
        svc.registerSchemas(registry);
        registry.migrate(svc.getServiceName(), state, startupNetworks);
        lenient().when(wsa.getState()).thenReturn(state);
        lenient().when(props.getConfiguration()).thenReturn(versionedConfig);
        lenient().when(versionedConfig.getConfigData(HederaConfig.class)).thenReturn(hederaConfig);
        lenient().when(hederaConfig.transactionMaxValidDuration()).thenReturn(180L);
        lenient().when(versionedConfig.getConfigData(LedgerConfig.class)).thenReturn(ledgerConfig);
        lenient().when(ledgerConfig.recordsMaxQueryableByAccount()).thenReturn(MAX_QUERYABLE_PER_ACCOUNT);
    }

    private TransactionID transactionID() {
        return transactionID(0);
    }

    private TransactionID transactionID(int nanos) {
        final var now = Instant.now();
        return TransactionID.newBuilder()
                .transactionValidStart(
                        Timestamp.newBuilder().seconds(now.getEpochSecond()).nanos(nanos))
                .accountID(PAYER_ACCOUNT_ID)
                .build();
    }

    @Test
    @DisplayName("Null args to constructor throw NPE")
    @SuppressWarnings("DataFlowIssue")
    void nullArgsToConstructorThrowNPE() {
        assertThatThrownBy(() -> new RecordCacheImpl(null, wsa, props, networkInfo))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RecordCacheImpl(dedupeCache, null, props, networkInfo))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RecordCacheImpl(dedupeCache, wsa, null, networkInfo))
                .isInstanceOf(NullPointerException.class);
    }

    private TransactionRecord getRecord(RecordCache cache, TransactionID txId) {
        final var history = cache.getHistory(txId);
        return history == null ? null : history.userTransactionRecord();
    }

    private TransactionReceipt getReceipt(RecordCache cache, TransactionID txId) {
        final var history = cache.getHistory(txId);
        return history == null ? null : history.priorityReceipt();
    }

    private List<TransactionRecord> getRecords(RecordCache cache, TransactionID txId) {
        final var history = cache.getHistory(txId);
        if (history == null) return List.of();
        return history.orderedRecords();
    }

    private List<TransactionReceipt> getReceipts(RecordCache cache, TransactionID txId) {
        return getRecords(cache, txId).stream().map(TransactionRecord::receipt).toList();
    }

    private List<TransactionReceipt> getReceipts(RecordCache cache, AccountID payerId) {
        return cache.getRecords(payerId).stream()
                .map(TransactionRecord::receipt)
                .toList();
    }

    @Nested
    @DisplayName("Rebuilds from state")
    final class RebuildTests {
        @Test
        @DisplayName("Construction adds all entries in state to the in-memory data structures")
        void reloadsIntoCacheOnConstruction() {
            // Given a state with some entries BEFORE the cache is constructed
            final var payer1 = accountId(1001);
            final var payer2 = accountId(1002);

            final var txId1 = transactionID();
            final var txId2 = txId1.copyBuilder().accountID(payer2).build();
            final var pTxId1 = txId1.copyBuilder().nonce(1).build();
            final var cTxId1 = txId1.copyBuilder().nonce(2).build();

            final var entries = List.of(
                    // preceding tx
                    new TransactionReceiptEntry(0, pTxId1, SUCCESS),
                    // user tx
                    new TransactionReceiptEntry(0, txId1, SUCCESS),
                    // child tx
                    new TransactionReceiptEntry(0, cTxId1, SUCCESS),
                    // user tx
                    new TransactionReceiptEntry(1, txId2, SUCCESS),
                    // duplicate  user tx
                    new TransactionReceiptEntry(2, txId2, SUCCESS),
                    // duplicate  user tx
                    new TransactionReceiptEntry(3, txId1, SUCCESS));

            final var entry = new TransactionReceiptEntries(entries);

            final var state = wsa.getState();
            assertThat(state).isNotNull();
            final var services = state.getWritableStates(RecordCacheService.NAME);
            final WritableQueueState<TransactionReceiptEntries> queue =
                    services.getQueue(TRANSACTION_RECEIPTS_STATE_ID);
            assertThat(queue).isNotNull();
            queue.add(entry);
            ((ListWritableQueueState<?>) queue).commit();

            // When we create the cache
            final var cache = new RecordCacheImpl(dedupeCache, wsa, props, networkInfo);

            final var entry0Record = asRecord(entries.get(0));
            final var entry1Record = asRecord(entries.get(1));
            final var entry2Record = asRecord(entries.get(2));
            final var entry3Record = asRecord(entries.get(3));
            final var entry4Record = asRecord(entries.get(4));
            final var entry5Record = asRecord(entries.get(5));
            // Everything that was in state can be queried
            assertThat(getRecord(cache, txId1)).isEqualTo(entry1Record);
            assertThat(getReceipt(cache, txId1)).isEqualTo(entry1Record.receipt());

            assertThat(getRecords(cache, txId1))
                    .containsExactlyInAnyOrder(entry0Record, entry1Record, entry2Record, entry5Record);
            assertThat(cache.getRecords(payer1))
                    .containsExactlyInAnyOrder(entry0Record, entry1Record, entry2Record, entry5Record);
            assertThat(getReceipts(cache, txId1))
                    .containsExactlyInAnyOrder(
                            entry0Record.receipt(),
                            entry1Record.receipt(),
                            entry2Record.receipt(),
                            entry5Record.receipt());
            assertThat(getReceipts(cache, payer1))
                    .containsExactlyInAnyOrder(
                            entry0Record.receipt(),
                            entry1Record.receipt(),
                            entry2Record.receipt(),
                            entry5Record.receipt());

            assertThat(getRecord(cache, txId2)).isEqualTo(entry3Record);
            assertThat(getReceipt(cache, txId2)).isEqualTo(entry3Record.receipt());
            assertThat(getRecords(cache, txId2)).containsExactly(entry3Record, entry4Record);
            assertThat(cache.getRecords(payer2)).containsExactly(entry3Record, entry4Record);
            assertThat(getReceipts(cache, txId2)).containsExactly(entry3Record.receipt(), entry4Record.receipt());
            assertThat(getReceipts(cache, payer2)).containsExactly(entry3Record.receipt(), entry4Record.receipt());
        }

        @Test
        @DisplayName("Rebuild replaces all entries in the in-memory data structures")
        void reloadsIntoCache() {
            final var state = wsa.getState();
            assertThat(state).isNotNull();
            final var services = state.getWritableStates(RecordCacheService.NAME);
            final WritableQueueState<TransactionReceiptEntries> queue =
                    services.getQueue(TRANSACTION_RECEIPTS_STATE_ID);

            // When we replace the data "behind the scenes" (emulating a reconnect) and call rebuild
            final var payer1 = accountId(1001);
            final var payer2 = accountId(1002);

            final var txId1 = transactionID();
            final var txId2 = txId1.copyBuilder().accountID(payer2).build();

            final var entries = List.of(
                    new TransactionReceiptEntry(0, txId1, SUCCESS),
                    new TransactionReceiptEntry(1, txId2, ACCOUNT_IS_IMMUTABLE),
                    new TransactionReceiptEntry(2, txId2, DUPLICATE_TRANSACTION),
                    new TransactionReceiptEntry(3, txId1, DUPLICATE_TRANSACTION));

            final var entry = new TransactionReceiptEntries(entries);
            queue.add(entry);
            ((ListWritableQueueState<?>) queue).commit();

            final var cache = new RecordCacheImpl(dedupeCache, wsa, props, networkInfo);

            final var entry0Record = asRecord(entries.get(0));
            final var entry1Record = asRecord(entries.get(1));
            final var entry2Record = asRecord(entries.get(2));
            final var entry3Record = asRecord(entries.get(3));

            // Then we find the new state is in the cache
            assertThat(getRecord(cache, txId1)).isEqualTo(entry0Record);
            assertThat(getReceipt(cache, txId1)).isEqualTo(entry0Record.receipt());
            assertThat(getRecords(cache, txId1)).containsExactly(entry0Record, entry3Record);
            assertThat(cache.getRecords(payer1)).containsExactly(entry0Record, entry3Record);
            assertThat(getReceipts(cache, txId1)).containsExactly(entry0Record.receipt(), entry3Record.receipt());
            assertThat(getReceipts(cache, payer1)).containsExactly(entry0Record.receipt(), entry3Record.receipt());

            assertThat(getRecord(cache, txId2)).isEqualTo(entry1Record);
            assertThat(getReceipt(cache, txId2)).isEqualTo(entry1Record.receipt());
            assertThat(getRecords(cache, txId2)).containsExactly(entry1Record, entry2Record);
            assertThat(cache.getRecords(payer2)).containsExactly(entry1Record, entry2Record);
            assertThat(getReceipts(cache, txId2)).containsExactly(entry1Record.receipt(), entry2Record.receipt());
            assertThat(getReceipts(cache, payer2)).containsExactly(entry1Record.receipt(), entry2Record.receipt());
        }

        private AccountID accountId(final int num) {
            return AccountID.newBuilder().accountNum(num).build();
        }
    }

    private TransactionRecord asRecord(final TransactionReceiptEntry transactionReceiptEntry) {
        return TransactionRecord.newBuilder()
                .transactionID(transactionReceiptEntry.transactionId())
                .receipt(TransactionReceipt.newBuilder().status(transactionReceiptEntry.status()))
                .build();
    }

    @Nested
    @DisplayName("Queries for receipts")
    final class ReceiptQueryTests {
        @Test
        @DisplayName("Query for receipt for no such txn returns null")
        void queryForReceiptForNoSuchTxnReturnsNull() {
            // Given a transaction unknown to the record cache and de-duplication cache
            final var cache = new RecordCacheImpl(dedupeCache, wsa, props, networkInfo);
            final var missingTxId = transactionID();

            // When we look up the receipt, then we get null
            assertThat(getReceipt(cache, missingTxId)).isNull();
        }

        @Test
        @DisplayName("Query for receipts for no such txn returns EMPTY LIST")
        void queryForReceiptsForNoSuchTxnReturnsNull() {
            // Given a transaction unknown to the record cache and de-duplication cache
            final var cache = new RecordCacheImpl(dedupeCache, wsa, props, networkInfo);
            final var missingTxId = transactionID();

            // When we look up the receipts, then we get an empty list
            assertThat(getReceipts(cache, missingTxId)).isEmpty();
        }

        @Test
        @DisplayName("Query for receipts for an account ID with no receipts returns EMPTY LIST")
        void queryForReceiptsForAccountWithNoRecords() {
            // Given a transaction unknown to the record cache and de-duplication cache
            final var cache = new RecordCacheImpl(dedupeCache, wsa, props, networkInfo);

            // When we look up the receipts, then we get an empty list
            assertThat(getReceipts(cache, PAYER_ACCOUNT_ID)).isEmpty();
        }

        @Test
        @DisplayName("Query for receipt for txn in UNKNOWN state returns UNKNOWN")
        void queryForReceiptForUnhandledTxnReturnsNull() {
            // Given a transaction known to the de-duplication cache but not the record cache
            final var cache = new RecordCacheImpl(dedupeCache, wsa, props, networkInfo);
            final var unhandledTxId = transactionID();
            dedupeCache.add(unhandledTxId);

            // When we query for the receipt, then we get the UNKNOWN receipt
            assertThat(getReceipt(cache, unhandledTxId)).isEqualTo(UNHANDLED_RECEIPT);
        }

        @Test
        @DisplayName("Query for receipts by account ID for txn in UNKNOWN state returns EMPTY LIST")
        void queryForReceiptsForUnhandledTxnByAccountID() {
            // Given a transaction known to the de-duplication cache but not the record cache
            final var cache = new RecordCacheImpl(dedupeCache, wsa, props, networkInfo);
            final var unhandledTxId = transactionID();
            dedupeCache.add(unhandledTxId);

            // Then even though the txn DOES have the payer account ID, we don't return it, because the transaction has
            // not
            // yet been handled, and it is only at handle time that we know for sure who paid for the transaction. In
            // addition, we didn't want to add complexity to account based lookup on the de-duplication cache.
            assertThat(getReceipts(cache, PAYER_ACCOUNT_ID)).isEmpty();
        }

        @ParameterizedTest
        @MethodSource("receiptStatusCodes")
        @DisplayName("Query for receipt for a txn with a proper record")
        void queryForReceiptForTxnWithRecord(@NonNull final ResponseCodeEnum status) {
            // Given a transaction known to the de-duplication cache but not the record cache
            final var cache = new RecordCacheImpl(dedupeCache, wsa, props, networkInfo);
            final var txId = transactionID();
            final var receipt = TransactionReceipt.newBuilder().status(status).build();
            final var record = TransactionRecord.newBuilder()
                    .transactionID(txId)
                    .receipt(receipt)
                    .build();

            // When the record is added to the cache
            cache.addRecordSource(0, txId, DueDiligenceFailure.NO, new PartialRecordSource(record));

            // Then we can query for the receipt by transaction ID
            assertThat(getReceipt(cache, txId)).isEqualTo(receipt);
        }

        @ParameterizedTest
        @MethodSource("receiptStatusCodes")
        @DisplayName("Query for receipts for a txn with a proper record")
        void queryForReceiptsForTxnWithRecord(@NonNull final ResponseCodeEnum status) {
            // Given a transaction known to the de-duplication cache but not the record cache
            final var cache = new RecordCacheImpl(dedupeCache, wsa, props, networkInfo);
            final var txId = transactionID();
            final var receipt = TransactionReceipt.newBuilder().status(status).build();
            final var record = TransactionRecord.newBuilder()
                    .transactionID(txId)
                    .receipt(receipt)
                    .build();

            // When the record is added to the cache
            cache.addRecordSource(0, txId, DueDiligenceFailure.NO, new PartialRecordSource(record));

            // Then we can query for the receipt by transaction ID
            assertThat(getReceipts(cache, txId)).containsExactly(receipt);
        }

        @ParameterizedTest
        @MethodSource("receiptStatusCodes")
        @DisplayName("Query for receipts for an account ID with a proper record")
        void queryForReceiptsForAccountIdWithRecord(@NonNull final ResponseCodeEnum status) {
            // Given a transaction known to the de-duplication cache but not the record cache
            final var cache = new RecordCacheImpl(dedupeCache, wsa, props, networkInfo);
            final var txId = transactionID();
            final var receipt = TransactionReceipt.newBuilder().status(status).build();
            final var record = TransactionRecord.newBuilder()
                    .transactionID(txId)
                    .receipt(receipt)
                    .build();

            // When the record is added to the cache
            cache.addRecordSource(0, txId, DueDiligenceFailure.NO, new PartialRecordSource(record));

            // Then we can query for the receipt by transaction ID
            assertThat(getReceipts(cache, PAYER_ACCOUNT_ID)).containsExactly(receipt);
        }

        @ParameterizedTest
        @ValueSource(ints = {20, 30, 40})
        @DisplayName(
                "Only up to recordsMaxQueryableByAccount receipts are returned for an account ID with multiple records")
        void queryForManyReceiptsForAccountID(final int numRecords) {
            // Given a number of transactions with several records each, all for the same payer
            final var cache = new RecordCacheImpl(dedupeCache, wsa, props, networkInfo);
            // Normally consensus time is AFTER the transaction ID time by a couple of seconds
            var consensusTime = Instant.now().plusSeconds(2);
            for (int i = 0; i < numRecords; i++) {
                final var txId = transactionID(i);
                for (int j = 0; j < 3; j++) {
                    consensusTime = consensusTime.plus(1, ChronoUnit.NANOS);
                    final var status = j == 0 ? OK : DUPLICATE_TRANSACTION;
                    final var receipt =
                            TransactionReceipt.newBuilder().status(status).build();
                    final var record = TransactionRecord.newBuilder()
                            .transactionID(txId)
                            .receipt(receipt)
                            .build();
                    cache.addRecordSource(0, txId, DueDiligenceFailure.NO, new PartialRecordSource(record));
                }
            }

            // When we query for the receipts for the payer account ID
            final var receipts = getReceipts(cache, PAYER_ACCOUNT_ID);

            // Then we get back the most recent recordsMaxQueryableByAccount receipts
            assertThat(receipts).hasSize(MAX_QUERYABLE_PER_ACCOUNT);
        }

        static Stream<Arguments> receiptStatusCodes() {
            final var allValues =
                    new HashSet<>(Arrays.asList(ResponseCodeEnum.values()).subList(0, RESPONSE_CODES_TO_TEST));
            allValues.remove(UNKNOWN);
            return allValues.stream().map(Arguments::of);
        }
    }

    @Nested
    @DisplayName("Query for records")
    final class RecordQueryTests {
        @Test
        @DisplayName("Query for record for unknown txn returns null")
        void queryForRecordForUnknownTxnReturnsNull() {
            final var cache = new RecordCacheImpl(dedupeCache, wsa, props, networkInfo);
            final var missingTxId = transactionID();

            assertThat(getRecord(cache, missingTxId)).isNull();
        }

        @Test
        @DisplayName("Query for records for unknown txn returns EMPTY LIST")
        void queryForRecordsForUnknownTxnReturnsNull() {
            final var cache = new RecordCacheImpl(dedupeCache, wsa, props, networkInfo);
            final var missingTxId = transactionID();

            assertThat(getRecords(cache, missingTxId)).isEmpty();
        }

        @Test
        @DisplayName("Query for record for account ID with no receipts returns EMPTY LIST")
        void queryForRecordByAccountForUnknownTxn() {
            final var cache = new RecordCacheImpl(dedupeCache, wsa, props, networkInfo);

            assertThat(cache.getRecords(PAYER_ACCOUNT_ID)).isEmpty();
        }

        @Test
        @DisplayName("Query for record for tx with receipt in UNKNOWN state returns null")
        void queryForRecordForUnknownTxn() {
            final var cache = new RecordCacheImpl(dedupeCache, wsa, props, networkInfo);
            final var txId = transactionID();
            dedupeCache.add(txId);

            assertThat(getRecord(cache, txId)).isNull();
        }

        @Test
        @DisplayName("Query for records for tx with receipt in UNKNOWN state returns EMPTY LIST")
        void queryForRecordsForUnknownTxn() {
            final var cache = new RecordCacheImpl(dedupeCache, wsa, props, networkInfo);
            final var txId = transactionID();
            dedupeCache.add(txId);

            assertThat(getRecords(cache, txId)).isEmpty();
        }

        @Test
        @DisplayName("Query for records for tx by account ID with receipt in UNKNOWN state returns EMPTY LIST")
        void queryForRecordsByAccountForUnknownTxn() {
            final var cache = new RecordCacheImpl(dedupeCache, wsa, props, networkInfo);
            final var txId = transactionID();
            dedupeCache.add(txId);

            assertThat(cache.getRecords(PAYER_ACCOUNT_ID)).isEmpty();
        }

        @ParameterizedTest
        @MethodSource("receiptStatusCodes")
        @DisplayName("Query for record for a txn with a proper record")
        void queryForRecordForTxnWithRecord(@NonNull final ResponseCodeEnum status) {
            // Given a transaction known to the de-duplication cache but not the record cache
            final var cache = new RecordCacheImpl(dedupeCache, wsa, props, networkInfo);
            final var txId = transactionID();
            final var receipt = TransactionReceipt.newBuilder().status(status).build();
            final var record = TransactionRecord.newBuilder()
                    .transactionID(txId)
                    .receipt(receipt)
                    .build();

            // When the record is added to the cache
            cache.addRecordSource(0, txId, DueDiligenceFailure.NO, new PartialRecordSource(record));

            // Then we can query for the receipt by transaction ID
            assertThat(getRecord(cache, txId)).isEqualTo(record);
        }

        @ParameterizedTest
        @MethodSource("receiptStatusCodes")
        @DisplayName("Query for records for a txn with a proper record")
        void queryForRecordsForTxnWithRecord(@NonNull final ResponseCodeEnum status) {
            // Given a transaction known to the de-duplication cache but not the record cache
            final var cache = new RecordCacheImpl(dedupeCache, wsa, props, networkInfo);
            final var txId = transactionID();
            final var tx = simpleCryptoTransfer(txId);
            final var receipt = TransactionReceipt.newBuilder().status(status).build();
            final var record = TransactionRecord.newBuilder()
                    .transactionID(txId)
                    .receipt(receipt)
                    .build();

            // When the record is added to the cache
            cache.addRecordSource(0, txId, DueDiligenceFailure.NO, new PartialRecordSource(record));

            // Then we can query for the receipt by transaction ID
            assertThat(getRecords(cache, txId)).containsExactly(record);
        }

        @Test
        void unclassifiableStatusIsNotPriority() {
            // Given a transaction known to the de-duplication cache but not the record cache
            final var cache = new RecordCacheImpl(dedupeCache, wsa, props, networkInfo);
            final var txId = transactionID();
            final var unclassifiableReceipt =
                    TransactionReceipt.newBuilder().status(INVALID_NODE_ACCOUNT).build();
            final var unclassifiableRecord = TransactionRecord.newBuilder()
                    .transactionID(txId)
                    .receipt(unclassifiableReceipt)
                    .build();
            final var classifiableReceipt =
                    TransactionReceipt.newBuilder().status(SUCCESS).build();
            final var classifiableRecord = TransactionRecord.newBuilder()
                    .transactionID(txId)
                    .receipt(classifiableReceipt)
                    .build();
            given(nodeInfo.accountId()).willReturn(NODE_ACCOUNT_ID);
            given(networkInfo.nodeInfo(0)).willReturn(nodeInfo);

            // When the unclassifiable record is added to the cache
            cache.addRecordSource(0, txId, DueDiligenceFailure.YES, new PartialRecordSource(unclassifiableRecord));
            // It does not prevent a "good" record from using this transaction id
            assertThat(cache.hasDuplicate(txId, 0L)).isEqualTo(NO_DUPLICATE);
            cache.addRecordSource(0, txId, DueDiligenceFailure.NO, new PartialRecordSource(classifiableRecord));

            // And we get the success record from userTransactionRecord()
            assertThat(cache.getHistory(txId)).isNotNull();
            final var userRecord =
                    Objects.requireNonNull(cache.getHistory(txId)).userTransactionRecord();
            assertThat(userRecord).isEqualTo(classifiableRecord);
        }

        @ParameterizedTest
        @MethodSource("receiptStatusCodes")
        @DisplayName("Query for records for an account ID with a proper record")
        void queryForRecordsForAccountIdWithRecord(@NonNull final ResponseCodeEnum status) {
            // Given a transaction known to the de-duplication cache but not the record cache
            final var cache = new RecordCacheImpl(dedupeCache, wsa, props, networkInfo);
            final var txId = transactionID();
            final var tx = simpleCryptoTransfer(txId);
            final var receipt = TransactionReceipt.newBuilder().status(status).build();
            final var record = TransactionRecord.newBuilder()
                    .transactionID(txId)
                    .receipt(receipt)
                    .build();

            // When the record is added to the cache
            cache.addRecordSource(0, txId, DueDiligenceFailure.NO, new PartialRecordSource(record));

            // Then we can query for the receipt by transaction ID
            assertThat(cache.getRecords(PAYER_ACCOUNT_ID)).containsExactly(record);
        }

        static Stream<Arguments> receiptStatusCodes() {
            final var allValues =
                    new HashSet<>(Arrays.asList(ResponseCodeEnum.values()).subList(0, RESPONSE_CODES_TO_TEST));
            allValues.remove(UNKNOWN);
            return allValues.stream().map(Arguments::of);
        }
    }

    @Nested
    @DisplayName("Duplicate checks")
    final class DuplicateCheckTests {

        @Test
        @DisplayName("Null args to hasDuplicate throw NPE")
        @SuppressWarnings("DataFlowIssue")
        void duplicateCheckWithIllegalParameters() {
            final var cache = new RecordCacheImpl(dedupeCache, wsa, props, networkInfo);
            assertThatThrownBy(() -> cache.hasDuplicate(null, 1L)).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Check duplicate for unknown txn returns NO_DUPLICATE")
        void duplicateCheckForUnknownTxn() {
            final var cache = new RecordCacheImpl(dedupeCache, wsa, props, networkInfo);
            final var missingTxId = transactionID();

            assertThat(cache.hasDuplicate(missingTxId, 1L)).isEqualTo(NO_DUPLICATE);
        }

        @Test
        @DisplayName("Check duplicate for tx with receipt in UNKNOWN state returns NO_DUPLICATE")
        void duplicateCheckForUnknownState() {
            final var cache = new RecordCacheImpl(dedupeCache, wsa, props, networkInfo);
            final var txId = transactionID();
            dedupeCache.add(txId);

            assertThat(cache.hasDuplicate(txId, 1L)).isEqualTo(NO_DUPLICATE);
        }

        @Test
        @DisplayName("Check duplicate for txn with a proper record from other node")
        void duplicateCheckForTxnFromOtherNode() {
            // Given a transaction known to the de-duplication cache but not the record cache
            final var cache = new RecordCacheImpl(dedupeCache, wsa, props, networkInfo);
            final var txId = transactionID();
            final var tx = simpleCryptoTransfer(txId);
            final var receipt = TransactionReceipt.newBuilder().status(OK).build();
            final var record = TransactionRecord.newBuilder()
                    .transactionID(txId)
                    .receipt(receipt)
                    .build();

            // When the record is added to the cache
            cache.addRecordSource(1L, txId, DueDiligenceFailure.NO, new PartialRecordSource(record));

            // Then we can check for a duplicate by transaction ID
            assertThat(cache.hasDuplicate(txId, 2L)).isEqualTo(OTHER_NODE);
        }

        @Test
        @DisplayName("Check duplicate for txn with a proper record from same node")
        void duplicateCheckForTxnFromSameNode() {
            // Given a transaction known to the de-duplication cache but not the record cache
            final var cache = new RecordCacheImpl(dedupeCache, wsa, props, networkInfo);
            final var txId = transactionID();
            final var tx = simpleCryptoTransfer(txId);
            final var receipt = TransactionReceipt.newBuilder().status(OK).build();
            final var record = TransactionRecord.newBuilder()
                    .transactionID(txId)
                    .receipt(receipt)
                    .build();

            // When the record is added to the cache
            cache.addRecordSource(1L, txId, DueDiligenceFailure.NO, new PartialRecordSource(record));

            // Then we can check for a duplicate by transaction ID
            assertThat(cache.hasDuplicate(txId, 1L)).isEqualTo(SAME_NODE);
        }

        @Test
        @DisplayName("Check duplicate for txn with a proper record from several other nodes")
        void duplicateCheckForTxnFromMultipleOtherNodes() {
            // Given a transaction known to the de-duplication cache but not the record cache
            final var cache = new RecordCacheImpl(dedupeCache, wsa, props, networkInfo);
            final var txId = transactionID();
            final var receipt = TransactionReceipt.newBuilder().status(OK).build();
            final var record = TransactionRecord.newBuilder()
                    .transactionID(txId)
                    .receipt(receipt)
                    .build();

            // When the record is added to the cache
            cache.addRecordSource(1L, txId, DueDiligenceFailure.NO, new PartialRecordSource(record));
            cache.addRecordSource(2L, txId, DueDiligenceFailure.NO, new PartialRecordSource(record));
            cache.addRecordSource(3L, txId, DueDiligenceFailure.NO, new PartialRecordSource(record));

            // Then we can check for a duplicate by transaction ID
            assertThat(cache.hasDuplicate(txId, 11L)).isEqualTo(OTHER_NODE);
        }

        @ParameterizedTest
        @ValueSource(longs = {1L, 2L, 3L})
        @DisplayName("Check duplicate for txn with a proper record from several nodes including the current")
        void duplicateCheckForTxnFromMultipleNodesIncludingCurrent(final long currentNodeId) {
            // Given a transaction known to the de-duplication cache but not the record cache
            final var cache = new RecordCacheImpl(dedupeCache, wsa, props, networkInfo);
            final var txId = transactionID();
            final var receipt = TransactionReceipt.newBuilder().status(OK).build();
            final var record = TransactionRecord.newBuilder()
                    .transactionID(txId)
                    .receipt(receipt)
                    .build();

            // When the record is added to the cache
            cache.addRecordSource(1L, txId, DueDiligenceFailure.NO, new PartialRecordSource(record));
            cache.addRecordSource(2L, txId, DueDiligenceFailure.NO, new PartialRecordSource(record));
            cache.addRecordSource(3L, txId, DueDiligenceFailure.NO, new PartialRecordSource(record));

            // Then we can check for a duplicate by transaction ID
            assertThat(cache.hasDuplicate(txId, currentNodeId)).isEqualTo(SAME_NODE);
        }
    }
}
