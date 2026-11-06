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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
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
                        .seconds(now.getEpochSecond() + 2 * MAX_TXN_DURATION)
                        .build())
                .build();

        // When we add it to the cache
        cache.add(txId);

        // Then it is not added, since it would not be pruned until its valid start
        assertThat(internalSet()).isEmpty();
        assertThat(cache.contains(txId)).isFalse();
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
    @DisplayName("TransactionIDs that expire are removed during add")
    void expungeDuringAdd() {
        // Given a transaction ID that has expired but is still in the cache
        final var now = Instant.now();
        final var txId = TransactionID.newBuilder()
                .transactionValidStart(Timestamp.newBuilder()
                        .seconds(now.getEpochSecond() - MAX_TXN_DURATION - 1)
                        .build())
                .build();
        addDirectly(txId);

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
        addDirectly(txId);

        // When we check to see if it is in the cache
        final var result = cache.contains(txId);

        // Then we find that the expired transaction ID is gone
        assertThat(result).isFalse();
        assertThat(internalSet()).isEmpty();
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

    @Test
    @DisplayName("Entries expire after the clock steps back and then moves forward again")
    void expiresAfterClockStepBack() {
        // Claude written

        // Given a cache driven by a controllable clock
        final var now = new AtomicReference<>(Instant.now());
        final InstantSource clock = now::get;
        final var localCache = new DeduplicationCacheImpl(props, clock);
        final var start = now.get().getEpochSecond();

        // And the clock has first run far ahead, pushing the prune cutoff high
        localCache.contains(txIdAt(start));
        now.set(Instant.ofEpochSecond(start + 1000));
        localCache.contains(txIdAt(start));

        // When the clock steps back and an entry that is valid at that time is added
        now.set(Instant.ofEpochSecond(start));
        final var txId = txIdAt(start - 100);
        localCache.add(txId);
        assertThat(localCache.contains(txId)).isTrue();

        // And the clock moves forward until the entry is expired, but below the previous high cutoff
        now.set(Instant.ofEpochSecond(start + 100));

        // Then the entry is no longer reported
        assertThat(localCache.contains(txId)).isFalse();
    }

    private static TransactionID txIdAt(final long validStartSecond) {
        return TransactionID.newBuilder()
                .transactionValidStart(
                        Timestamp.newBuilder().seconds(validStartSecond).build())
                .build();
    }

    /**
     * Utility method for testing purposes that gets at the internal buckets used by the cache. This makes it possible
     * to test more completely without having to open the access permissions on the cache itself.
     *
     * @return The internal map of valid-start epoch second to the transaction IDs in that bucket.
     */
    private ConcurrentHashMap<Long, Set<TransactionID>> internalBuckets() {
        try {
            final var field = DeduplicationCacheImpl.class.getDeclaredField("submittedTxns");
            field.setAccessible(true);
            //noinspection unchecked
            return (ConcurrentHashMap<Long, Set<TransactionID>>) field.get(cache);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    /** Returns every cached transaction ID, ordered by valid-start second (earliest first). */
    private List<TransactionID> internalSet() {
        return internalBuckets().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .flatMap(entry -> entry.getValue().stream())
                .toList();
    }

    /** Puts a transaction ID straight into the cache's internal bucket, bypassing {@code add}'s pruning. */
    private void addDirectly(final TransactionID txId) {
        internalBuckets()
                .computeIfAbsent(txId.transactionValidStartOrThrow().seconds(), second -> ConcurrentHashMap.newKeySet())
                .add(txId);
    }
}
