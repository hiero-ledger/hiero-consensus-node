// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.schemas;

import static com.hedera.hapi.util.HapiUtils.CONTRACT_ID_COMPARATOR;
import static com.hedera.node.app.service.contract.impl.schemas.V0490ContractSchema.STORAGE_KEY;
import static java.util.Comparator.naturalOrder;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.contract.SlotKey;
import com.hedera.hapi.node.state.contract.SlotValue;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.spi.ReadableKVState;
import com.swirlds.state.spi.WritableKVState;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A schema that analyzes the storage mappings in state and does two things:
 * <ol>
 *     <li>For any contract with broken links, repairs the links by relinking its
 *     storage mappings in sorted key order.</li>
 *     <li>For every contract, publishes its correct first key in the shared
 *     migration context in a {@link SortedMap} at key {@code "V0500_FIRST_STORAGE_KEYS"}.</li>
 * </ol>
 */
public class V0500ContractSchema extends Schema {
    private static final Logger log = LogManager.getLogger(V0500ContractSchema.class);

    private static final long MAX_SUPPORTED_STORAGE_SIZE = 4_000_000L;

    private static final String SHARED_VALUES_KEY = "V0500_FIRST_STORAGE_KEYS";

    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().major(0).minor(50).patch(0).build();

    public V0500ContractSchema() {
        super(VERSION);
    }

    private record StorageMapping(@NonNull SlotKey slotKey, @NonNull SlotValue slotValue)
            implements Comparable<StorageMapping> {
        @Override
        public int compareTo(@NonNull final StorageMapping that) {
            return this.slotKey().key().compareTo(that.slotKey().key());
        }
    }

    private record MappingSummary(@NonNull Bytes firstKey, @Nullable List<StorageMapping> fixedMappings) {}

    @Override
    public void migrate(@NonNull final MigrationContext ctx) {
        requireNonNull(ctx);
        final ReadableKVState<SlotKey, SlotValue> storage = ctx.previousStates().get(STORAGE_KEY);
        final var begin = Instant.now();
        final Map<ContractID, List<StorageMapping>> mappings = new HashMap<>();
        final SortedMap<ContractID, Bytes> firstKeys = new TreeMap<>(CONTRACT_ID_COMPARATOR);
        // Collect all storage mappings from the previous state
        storage.keys()
                .forEachRemaining(key -> mappings.computeIfAbsent(key.contractIDOrThrow(), ignore -> new ArrayList<>())
                        .add(new StorageMapping(key, requireNonNull(storage.get(key)))));

        // Relink any broken storage mappings, while removing any that were already intact
        new ArrayList<>(mappings.keySet()).forEach(contractId -> {
            final var summary = summarizeWithRequiredFixes(mappings.get(contractId));
            firstKeys.put(contractId, summary.firstKey());
            if (summary.fixedMappings() != null) {
                mappings.put(contractId, summary.fixedMappings());
            } else {
                mappings.remove(contractId);
            }
        });

        final List<ContractID> contractIdsToMigrate = new ArrayList<>(mappings.keySet());
        if (!contractIdsToMigrate.isEmpty()) {
            contractIdsToMigrate.sort(CONTRACT_ID_COMPARATOR);
            final WritableKVState<SlotKey, SlotValue> writableStorage =
                    ctx.newStates().get(STORAGE_KEY);
            // And finally update the new state with the fixed mappings
            contractIdsToMigrate.forEach(contractId -> mappings.get(contractId)
                    .forEach(mapping -> writableStorage.put(mapping.slotKey(), mapping.slotValue())));
        }

        // Expose the first keys of all contracts in the migration context for the token service
        ctx.sharedValues().put(SHARED_VALUES_KEY, firstKeys);
        final var end = Instant.now();
        log.info("Completed link repair in {}", Duration.between(begin, end));
    }

    private MappingSummary summarizeWithRequiredFixes(@NonNull final List<StorageMapping> mappings) {
        requireNonNull(mappings);
        // Identify the first and last mappings and build a key-to-index map
        StorageMapping first = null;
        StorageMapping last = null;
        final Map<Bytes, Integer> locations = HashMap.newHashMap(mappings.size());
        int location = 0;
        for (final var mapping : mappings) {
            if (Bytes.EMPTY.equals(mapping.slotValue().previousKey())) {
                first = mapping;
            }
            if (Bytes.EMPTY.equals(mapping.slotValue().nextKey())) {
                last = mapping;
            }
            locations.put(mapping.slotKey().key(), location++);
        }
        if (hasIntactLinks(first, last, locations, mappings)) {
            return new MappingSummary(first.slotKey().key(), null);
        } else {
            final var fixedMappings = fixBrokenLinks(mappings);
            return new MappingSummary(fixedMappings.getFirst().slotKey().key(), fixedMappings);
        }
    }

    private @NonNull List<StorageMapping> fixBrokenLinks(@NonNull final List<StorageMapping> mappings) {
        mappings.sort(naturalOrder());
        final List<StorageMapping> relinked = new ArrayList<>(mappings.size());
        for (int i = 0, n = mappings.size(); i < n; i++) {
            final var previousKey =
                    i == 0 ? Bytes.EMPTY : mappings.get(i - 1).slotKey().key();
            final var nextKey =
                    i == n - 1 ? Bytes.EMPTY : mappings.get(i + 1).slotKey().key();
            final var mapping = mappings.get(i);
            relinked.add(new StorageMapping(
                    mapping.slotKey(), new SlotValue(mapping.slotValue().value(), previousKey, nextKey)));
        }
        return relinked;
    }

    private boolean hasIntactLinks(
            @Nullable final StorageMapping first,
            @Nullable final StorageMapping last,
            @NonNull final Map<Bytes, Integer> locations,
            @NonNull final List<StorageMapping> mappings) {
        if (first != null && last != null) {
            // Given a first and last mapping, we can check if the chain is intact; that is, if...
            //   (1) Each mapping points to a next mapping whose previous key is that mapping's key; and,
            //   (2) Following the chain from the first mapping leads to the last mapping; and,
            //   (3) Every mapping in the chain is seen exactly once.
            int seen = 1;
            StorageMapping current = first;
            for (int i = 0, n = mappings.size() - 1; i < n; i++, seen++) {
                final var next = locations.get(current.slotValue().nextKey());
                if (next == null
                        || !current.slotKey()
                                .key()
                                .equals(mappings.get(next).slotValue().previousKey())) {
                    break;
                } else {
                    current = mappings.get(next);
                }
            }
            return seen == mappings.size() && current == last;
        }
        return false;
    }
}
