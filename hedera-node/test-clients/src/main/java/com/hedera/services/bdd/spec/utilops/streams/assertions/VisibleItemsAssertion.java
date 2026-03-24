// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.streams.assertions;

import static com.hedera.services.bdd.spec.utilops.streams.assertions.BaseIdScreenedAssertion.baseFieldsMatch;
import static com.hedera.services.bdd.spec.utilops.streams.assertions.VisibleItems.newVisibleItems;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.NodeStakeUpdate;
import static java.util.Objects.requireNonNull;

import com.hedera.node.app.hapi.utils.forensics.RecordStreamEntry;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.stream.proto.RecordStreamItem;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class VisibleItemsAssertion implements RecordStreamAssertion {
    private static final long FIRST_USER_NUM = 1001L;
    public static final String ALL_TX_IDS = "all-tx-ids";

    private static final Logger log = LogManager.getLogger(VisibleItemsAssertion.class);

    private final HapiSpec spec;
    private final Set<String> allIds;
    private final VisibleItemsValidator validator;
    private final Map<String, VisibleItems> items = new ConcurrentHashMap<>();
    private final boolean withLogging = true;
    private final boolean viewAll;

    /**
     * Cached flag that becomes true once every expected ID has at least one
     * collected item in the {@link #items} map, so we avoid re-evaluating
     * {@code allIds.stream().allMatch(items::containsKey)} on every stream item.
     */
    private boolean allIdsHaveItems = false;

    /**
     * The total number of collected entries the last time validation was
     * attempted.  Re-validation is skipped unless new items have arrived,
     * avoiding repeated validator + exception overhead on every stream item.
     */
    private int itemCountAtLastValidation = -1;

    /**
     * The most recent validation error, kept so that {@link #toString()} can
     * include it in timeout messages for better diagnostics.
     */
    @Nullable
    private AssertionError lastValidationError = null;

    private final SkipSynthItems skipSynthItems;

    public enum SkipSynthItems {
        YES,
        NO
    }

    public VisibleItemsAssertion(
            @NonNull final HapiSpec spec,
            @NonNull final VisibleItemsValidator validator,
            @NonNull final SkipSynthItems skipSynthItems,
            @NonNull final String... specTxnIds) {
        this.spec = requireNonNull(spec);
        this.validator = validator;
        this.skipSynthItems = requireNonNull(skipSynthItems);
        allIds = Set.copyOf(List.of(specTxnIds));
        viewAll = allIds.contains(ALL_TX_IDS);
    }

    @Override
    public String toString() {
        final var sb = new StringBuilder("VisibleItemsAssertion{seenIds=")
                .append(items.keySet())
                .append(", allIds=")
                .append(allIds);
        if (lastValidationError != null) {
            sb.append(", lastError=").append(lastValidationError.getMessage());
        }
        return sb.append('}').toString();
    }

    @Override
    public synchronized boolean isApplicableTo(@NonNull final RecordStreamItem item) {
        if (viewAll) {
            final var entry = RecordStreamEntry.from(item);
            final var isSynthItem = isSynthItem(entry);
            if (skipSynthItems == SkipSynthItems.NO || !isSynthItem) {
                items.computeIfAbsent(ALL_TX_IDS, ignore -> newVisibleItems())
                        .entries()
                        .add(entry);
            }
        } else {
            new ArrayList<>(allIds)
                    .stream()
                            .filter(id -> spec.registry()
                                    .getMaybeTxnId(id)
                                    .filter(txnId -> baseFieldsMatch(
                                            txnId, item.getRecord().getTransactionID()))
                                    .isPresent())
                            .findFirst()
                            .ifPresent(seenId -> {
                                final var entry = RecordStreamEntry.from(item);
                                final var isSynthItem = isSynthItem(entry);
                                if (skipSynthItems == SkipSynthItems.NO || !isSynthItem) {
                                    if (withLogging) {
                                        log.info(
                                                "Saw {} as {}",
                                                seenId,
                                                item.getRecord().getTransactionID());
                                    }
                                    items.computeIfAbsent(seenId, ignore -> newVisibleItems())
                                            .entries()
                                            .add(entry);
                                } else {
                                    items.computeIfAbsent(seenId, ignore -> newVisibleItems())
                                            .trackSkippedSynthItem();
                                }
                            });
        }
        return true;
    }

    @Override
    public boolean test(@NonNull final RecordStreamItem item) throws AssertionError {
        if (viewAll) {
            validator.assertValid(spec, items);
            return false;
        }
        // Only attempt validation once every ID has at least one collected item;
        // use try/catch so that if items are still arriving (e.g. a preceding child
        // and its parent were split across record-stream files), we keep waiting
        // instead of failing immediately on incomplete data.
        if (!allIdsHaveItems) {
            allIdsHaveItems = allIds.stream().allMatch(items::containsKey);
        }
        if (allIdsHaveItems) {
            // Only re-run the validator when new items have arrived since the
            // last failed attempt, to avoid repeated exception/log overhead on
            // every stream item while waiting for late-arriving records.
            final var currentItemCount =
                    items.values().stream().mapToInt(v -> v.entries().size()).sum();
            if (currentItemCount == itemCountAtLastValidation) {
                return false;
            }
            try {
                validator.assertValid(spec, items);
                lastValidationError = null;
                return true;
            } catch (final AssertionError e) {
                itemCountAtLastValidation = currentItemCount;
                lastValidationError = e;
                if (withLogging) {
                    log.info("Validation not yet passing (items may still be arriving): {}", e.getMessage());
                }
                return false;
            }
        }
        return false;
    }

    private static boolean isSynthItem(@NonNull final RecordStreamEntry entry) {
        final var receipt = entry.transactionRecord().getReceipt();
        return entry.function() == NodeStakeUpdate
                || (receipt.getAccountID().hasAccountNum()
                        && receipt.getAccountID().getAccountNum() < FIRST_USER_NUM)
                || (receipt.hasFileID() && receipt.getFileID().getFileNum() < FIRST_USER_NUM);
    }
}
