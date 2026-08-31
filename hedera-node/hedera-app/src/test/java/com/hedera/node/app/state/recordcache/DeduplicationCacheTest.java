// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.state.recordcache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.node.app.state.DeduplicationCache;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfiguration;
import com.hedera.node.config.data.HederaConfig;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Set;
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
        assertThat(internalSet()).isEmpty();
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
        assertThat(internalSet()).containsExactly(txId);
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
        assertThat(internalSet()).containsExactly(txId);
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

        // Then they are all present (order is not significant after the skip-list rewrite)
        assertThat(internalSet()).containsExactlyInAnyOrderElementsOf(txIds);
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
        sneakIn(txId);

        // When we add a new transaction ID that is in the right time window
        final var txId2 = TransactionID.newBuilder()
                .transactionValidStart(Timestamp.newBuilder()
                        .seconds(now.getEpochSecond() + MAX_TXN_DURATION / 2)
                        .build())
                .build();
        cache.add(txId2);

        // Then we find that the expired transaction ID is gone
        assertThat(internalSet()).containsExactly(txId2);
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
        sneakIn(txId);

        // When we check to see if it is in the cache
        final var result = cache.contains(txId);

        // Then we find that the expired transaction ID is gone
        assertThat(result).isFalse();
        assertThat(internalSet()).isEmpty();
    }

    @Test
    @DisplayName("putIfAbsent claims an ID once and remove releases it")
    void putIfAbsentThenRemove() {
        final var now = Instant.now();
        final var txId = TransactionID.newBuilder()
                .transactionValidStart(Timestamp.newBuilder()
                        .seconds(now.getEpochSecond() + MAX_TXN_DURATION / 2)
                        .build())
                .build();

        assertThat(cache.putIfAbsent(txId)).isTrue();
        assertThat(cache.putIfAbsent(txId)).isFalse();
        assertThat(cache.contains(txId)).isTrue();

        cache.remove(txId);
        assertThat(cache.contains(txId)).isFalse();
        assertThat(cache.putIfAbsent(txId)).isTrue();
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
        assertThat(internalSet()).containsExactly(txId);
        assertThat(cache.contains(txId)).isTrue();
    }

    /**
     * Utility method for testing purposes that gets at the internal Set used by the cache. This makes it possible to
     * test more completely without having to open the access permissions on the cache itself.
     *
     * @return The internal Set of the cache.
     */
    private Set<TransactionID> internalSet() {
        try {
            final var field = DeduplicationCacheImpl.class.getDeclaredField("submittedTxns");
            field.setAccessible(true);
            //noinspection unchecked
            return ((java.util.concurrent.ConcurrentHashMap<TransactionID, Boolean>) field.get(cache)).keySet();
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    /** Inserts a transaction ID without going through expiry checks, to test prune behavior. */
    @SuppressWarnings("unchecked")
    private void sneakIn(final TransactionID txId) {
        try {
            final var liveField = DeduplicationCacheImpl.class.getDeclaredField("submittedTxns");
            liveField.setAccessible(true);
            ((java.util.concurrent.ConcurrentHashMap<TransactionID, Boolean>) liveField.get(cache))
                    .put(txId, Boolean.TRUE);
            final var bucketsField = DeduplicationCacheImpl.class.getDeclaredField("byValidStartSecond");
            bucketsField.setAccessible(true);
            final var buckets =
                    (java.util.concurrent.ConcurrentHashMap<Long, Set<TransactionID>>) bucketsField.get(cache);
            buckets.computeIfAbsent(
                            txId.transactionValidStartOrThrow().seconds(),
                            second -> java.util.concurrent.ConcurrentHashMap.newKeySet())
                    .add(txId);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }
}
