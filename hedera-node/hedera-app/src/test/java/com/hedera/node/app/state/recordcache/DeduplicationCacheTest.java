// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.state.recordcache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.node.app.state.DeduplicationCache;
import com.hedera.node.app.state.recordcache.DeduplicationCacheImpl.TxStatus;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfiguration;
import com.hedera.node.config.data.HederaConfig;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
final class DeduplicationCacheTest {
    private static final long MAX_TXN_DURATION = 180;

    private DeduplicationCache cache;

    @Mock
    private ConfigProvider props;

    private final InstantSource instantSource = InstantSource.system();

    @BeforeEach
    void setUp(@Mock final VersionedConfiguration versionedConfig, @Mock final HederaConfig hederaConfig) {
        cache = new DeduplicationCacheImpl(props, instantSource);
        lenient().when(props.getConfiguration()).thenReturn(versionedConfig);
        lenient().when(versionedConfig.getConfigData(HederaConfig.class)).thenReturn(hederaConfig);
        lenient().when(hederaConfig.transactionMaxValidDuration()).thenReturn(MAX_TXN_DURATION);
    }

    @Test
    @DisplayName("Constructor args cannot be null")
    void constructorArgsCannotBeNull() {
        //noinspection DataFlowIssue
        assertThatThrownBy(() -> new DeduplicationCacheImpl(null, instantSource))
                .isInstanceOf(NullPointerException.class);
    }

    // Add a transaction ID that has expired
    @Test
    @DisplayName("Add a transaction ID that has expired")
    void addTransactionIDThatHasExpired() {
        // Given a transaction ID that has expired
        final var now = Instant.now();
        final var txId = TransactionID.newBuilder()
                .transactionValidStart(Timestamp.newBuilder()
                        .seconds(now.getEpochSecond() - MAX_TXN_DURATION - 1)
                        .build())
                .build();

        // When we add it to the cache
        cache.add(txId);

        // Then it is not added!
        assertThat(internalMap()).isEmpty();
        assertThat(cache.contains(txId)).isFalse();
    }

    @Test
    @DisplayName("Add a transaction ID that is far in the future")
    void addTransactionIDInTheFuture() {
        // Given a transaction ID that is far in the future
        final var now = Instant.now();
        final var txId = TransactionID.newBuilder()
                .transactionValidStart(Timestamp.newBuilder()
                        .seconds(now.getEpochSecond() + MAX_TXN_DURATION + 1)
                        .build())
                .build();

        // When we add it to the cache
        cache.add(txId);

        // We allow it to be added. The TransactionChecker is responsible for filtering out future transactions,
        // not this cache.
        assertThat(internalMap()).containsOnlyKeys(txId);
        assertThat(cache.contains(txId)).isTrue();
    }

    @Test
    @DisplayName("Add a transaction ID that is in the right time window")
    void addTransactionIDInTheRightTimeWindow() {
        // Given a transaction ID that is in the right time window
        final var now = Instant.now();
        final var txId = TransactionID.newBuilder()
                .transactionValidStart(Timestamp.newBuilder()
                        .seconds(now.getEpochSecond() + MAX_TXN_DURATION / 2)
                        .build())
                .build();

        // When we add it to the cache
        cache.add(txId);

        // Then it is added
        assertThat(internalMap()).containsOnlyKeys(txId);
        assertThat(cache.contains(txId)).isTrue();
    }

    @Test
    @DisplayName("TransactionIDs are sorted by earliest date first")
    void transactionIDsAreSortedByEarliestDateFirst() {
        // Given some transaction IDs with different valid start times
        final var now = Instant.now();
        final var txIds = Stream.of(17, 16, 10, 13, 19, 14, 11, 18, 12, 15)
                .map(i -> TransactionID.newBuilder()
                        .transactionValidStart(Timestamp.newBuilder()
                                .seconds(now.getEpochSecond() + i)
                                .build())
                        .build())
                .toList();

        // When we add them to the cache
        txIds.forEach(cache::add);

        // Then they are added in order
        assertThat(internalMap())
                .containsOnlyKeys(
                        txIds.get(2),
                        txIds.get(6),
                        txIds.get(8),
                        txIds.get(3),
                        txIds.get(5),
                        txIds.get(9),
                        txIds.get(1),
                        txIds.get(0),
                        txIds.get(7),
                        txIds.get(4));
    }

    @Test
    @DisplayName("TransactionIDs that expire are removed during add")
    void expungeDuringAdd() {
        // Given a transaction ID that has expired but is still in the cache
        final var now = Instant.now();
        final var txId = TransactionID.newBuilder()
                .transactionValidStart(Timestamp.newBuilder()
                        .seconds(now.getEpochSecond() - MAX_TXN_DURATION - 1)
                        .build())
                .build();
        internalMap().put(txId, TxStatus.SUBMITTED);

        // When we add a new transaction ID that is in the right time window
        final var txId2 = TransactionID.newBuilder()
                .transactionValidStart(Timestamp.newBuilder()
                        .seconds(now.getEpochSecond() + MAX_TXN_DURATION / 2)
                        .build())
                .build();
        cache.add(txId2);

        // Then we find that the expired transaction ID is gone
        assertThat(internalMap()).containsOnlyKeys(txId2);
    }

    @Test
    @DisplayName("TransactionIDs that expire are not returned by `contains`")
    void expiredNotContained() {
        // Given a transaction ID that has expired but is still in the cache
        final var now = Instant.now();
        final var txId = TransactionID.newBuilder()
                .transactionValidStart(Timestamp.newBuilder()
                        .seconds(now.getEpochSecond() - MAX_TXN_DURATION - 1)
                        .build())
                .build();
        internalMap().put(txId, TxStatus.SUBMITTED);

        // When we check to see if it is in the cache
        final var result = cache.contains(txId);

        // Then we find that the expired transaction ID is gone
        assertThat(result).isFalse();
        assertThat(internalMap()).isEmpty();
    }

    @Test
    @DisplayName("Duplicates are ignored")
    void duplicatesAreIgnored() {
        // Given a transaction ID that is in the right time window
        final var now = Instant.now();
        final var txId = TransactionID.newBuilder()
                .transactionValidStart(Timestamp.newBuilder()
                        .seconds(now.getEpochSecond() + MAX_TXN_DURATION / 2)
                        .build())
                .build();

        // When we add it to the cache twice
        cache.add(txId);
        cache.add(txId);

        // Then it is added only once
        assertThat(internalMap()).containsOnlyKeys(txId);
        assertThat(cache.contains(txId)).isTrue();
    }

    @Test
    @DisplayName("markStale updates transaction status to STALE")
    void markStaleUpdatesStatus() {
        // Given a transaction in the cache
        final var txId = createTransactionID();
        cache.add(txId);
        assertThat(cache.getTxStatus(txId) == TxStatus.SUBMITTED).isTrue();

        // When marking it as stale
        cache.markStale(txId);

        // Then the status is updated to STALE
        assertThat(cache.getTxStatus(txId) == TxStatus.STALE).isTrue();
    }

    @Test
    @DisplayName("add clears STALE status")
    void addClearsStaleUpdatesStatus() {
        // Given a transaction marked as stale
        final var txId = createTransactionID();
        cache.add(txId);
        cache.markStale(txId);

        // When clearing the stale status
        cache.add(txId);

        // Then the map no longer contains the transaction ID so isStale returns false
        assertThat(cache.getTxStatus(txId) == TxStatus.SUBMITTED).isTrue();
    }

    @Test
    @DisplayName("clear removes all transactions from the cache")
    void clearRemovesAllTransactions() {
        // Given multiple transactions in the cache
        final var txId1 = createTransactionID();
        final var txId2 = createTransactionID();
        cache.add(txId1);
        cache.add(txId2);

        // When clearing the cache
        cache.clear();

        // Then the cache is empty
        assertThat(cache.contains(txId1)).isFalse();
        assertThat(cache.contains(txId2)).isFalse();
    }

    private TransactionID createTransactionID() {
        final var now = Instant.now();
        return TransactionID.newBuilder()
                .transactionValidStart(
                        Timestamp.newBuilder().seconds(now.getEpochSecond()).build())
                .build();
    }

    /**
     * Utility method for testing purposes that gets at the internal Map used by the cache. This makes it possible to
     * test more completely without having to open the access permissions on the cache itself.
     *
     * @return The internal Map of the cache.
     */
    private Map<TransactionID, TxStatus> internalMap() {
        try {
            final var field = DeduplicationCacheImpl.class.getDeclaredField("submittedTxns");
            field.setAccessible(true);
            //noinspection unchecked
            return (Map<TransactionID, TxStatus>) field.get(cache);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }
}
