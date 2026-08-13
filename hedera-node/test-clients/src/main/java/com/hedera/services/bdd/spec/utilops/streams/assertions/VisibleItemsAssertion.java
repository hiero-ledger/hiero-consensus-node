// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.streams.assertions;

import static com.hedera.node.app.hapi.utils.CommonUtils.timestampToInstant;
import static com.hedera.services.bdd.spec.utilops.streams.assertions.BaseIdScreenedAssertion.baseFieldsMatch;
import static com.hedera.services.bdd.spec.utilops.streams.assertions.VisibleItems.newVisibleItems;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.NodeStakeUpdate;
import static java.util.Objects.requireNonNull;

import com.hedera.node.app.hapi.utils.forensics.RecordStreamEntry;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.infrastructure.HapiSpecRegistry;
import com.hedera.services.stream.proto.RecordStreamItem;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
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
    private final HapiSpecRegistry registry;
    private final Set<String> allIds;
    private final VisibleItemsValidator validator;
    private final Map<String, VisibleItems> items = new ConcurrentHashMap<>();
    // [block-assert-diag] Last unseen-id set logged, so the "waiting on" diagnostic only fires on change.
    private List<String> lastLoggedMissing = null;
    private final boolean withLogging = true;
    private final boolean viewAll;

    /**
     * Upper bound on {@link #pendingItems} size.  One record-stream file typically
     * holds tens of items; 500 covers several files and is more than enough for the
     * brief window before all {@code .via()} IDs are registered.
     */
    private static final int MAX_PENDING_ITEMS = 500;

    /**
     * Items that did not match any registered spec ID when first seen.  These are
     * retried on each subsequent {@link #isApplicableTo} call because the spec
     * registry may not yet have had the transaction ID registered at the time the
     * record-stream file was processed (the monitor thread can read a flushed file
     * before the main test thread finishes the {@code .via()} registration).
     * Capped at {@link #MAX_PENDING_ITEMS} to bound memory; oldest items are
     * dropped on overflow.
     */
    private final List<RecordStreamItem> pendingItems = new ArrayList<>();

    /**
     * Cached flag that becomes true once every expected ID has at least one
     * collected item in the {@link #items} map, so we avoid re-evaluating
     * {@code allIds.stream().allMatch(items::containsKey)} on every stream item.
     */
    private boolean allIdsHaveItems = false;

    /**
     * Snapshot of the total collected state (entries + skipped synth items) the
     * last time validation was attempted.  Re-validation is skipped unless state
     * has changed, avoiding repeated validator + exception overhead on every
     * stream item.
     */
    private int stateSnapshotAtLastValidation = -1;

    /**
     * Number of consecutive {@link #test} calls where the state snapshot has not
     * changed since the last failed validation.  Once this exceeds
     * {@link #SETTLE_ITEM_THRESHOLD}, we conclude that no more items are arriving
     * and re-throw the validation error so that it is reported as a genuine failure
     * (instead of being silently swallowed until timeout).
     */
    private int unchangedCallsSinceLastFailure = 0;

    /**
     * How many consecutive unchanged {@link #test} calls to tolerate before
     * re-throwing a validation error.  50 items covers roughly one record-stream
     * file's worth of traffic, giving enough time for a transaction's items that
     * were split across files to arrive in the next poll cycle.
     */
    private static final int SETTLE_ITEM_THRESHOLD = 50;

    /**
     * The most recent validation error, kept so that {@link #toString()} can
     * include it in timeout messages for better diagnostics.
     */
    @Nullable
    private AssertionError lastValidationError = null;

    /**
     * Consensus timestamps of items already processed, used to prevent
     * duplicate delivery (e.g. when {@code retryExposingVia} in
     * {@link com.hedera.services.bdd.junit.support.StreamFileAlterationListener}
     * re-reads a record stream file after a listener exception).
     */
    private final Set<Instant> seenConsensusTimestamps = ConcurrentHashMap.newKeySet();

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
        this.registry = requireNonNull(spec.registry());
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
                .append(allIds)
                .append(", pending=")
                .append(pendingItems.size());
        if (lastValidationError != null) {
            sb.append(", lastError=").append(lastValidationError.getMessage());
        }
        return sb.append('}').toString();
    }

    @Override
    public synchronized boolean isApplicableTo(@NonNull final RecordStreamItem item) {
        if (viewAll) {
            final var consensusTime = timestampToInstant(item.getRecord().getConsensusTimestamp());
            if (!seenConsensusTimestamps.add(consensusTime)) {
                return true; // duplicate delivery, skip
            }
            final var entry = RecordStreamEntry.from(item);
            final var isSynthItem = isSynthItem(entry);
            if (skipSynthItems == SkipSynthItems.NO || !isSynthItem) {
                items.computeIfAbsent(ALL_TX_IDS, ignore -> newVisibleItems())
                        .entries()
                        .add(entry);
            }
        } else {
            // Re-check any previously unmatched items against the registry, since
            // transaction IDs may have been registered after those items were first seen.
            sweepPendingItems();
            // Now try to match the current item.
            if (!tryMatch(item)) {
                if (pendingItems.size() >= MAX_PENDING_ITEMS) {
                    pendingItems.removeFirst();
                }
                pendingItems.add(item);
            }
        }
        return true;
    }

    @Override
    public boolean test(@NonNull final RecordStreamItem item) throws AssertionError {
        if (viewAll) {
            validator.assertValid(spec, items);
            return false;
        }
        return tryValidateNow();
    }

    /**
     * Re-checks any items buffered pending later transaction-ID registration, then re-evaluates,
     * independent of a new record arriving. Invoked once per delivered block (including blocks that
     * translate to zero records) by {@link RecordStreamToBlockAssertionAdapter}. Without this, under
     * {@code blockStream.writerMode=GRPC} the stream can go idle once a spec has submitted its
     * transactions (subsequent blocks carry no user records, so {@link #isApplicableTo} is never
     * called), and a buffered item whose {@code .via} id was registered just after it was first seen
     * would never be re-matched, causing a spurious timeout.
     *
     * @throws AssertionError if a genuine validation mismatch has settled
     * @return true if the assertion has now passed
     */
    @Override
    public synchronized boolean recheckPending() throws AssertionError {
        if (viewAll) {
            return false;
        }
        sweepPendingItems();
        return tryValidateNow();
    }

    /**
     * Re-checks previously unmatched items against the registry, since transaction IDs may have been
     * registered after those items were first seen; matched items are moved out of the pending buffer.
     */
    private void sweepPendingItems() {
        if (!pendingItems.isEmpty() && !allIdsHaveItems) {
            final var it = pendingItems.iterator();
            while (it.hasNext()) {
                if (tryMatch(it.next())) {
                    it.remove();
                }
            }
        }
    }

    /**
     * Attempts validation once every expected ID has at least one collected item. Uses try/catch so
     * that if items are still arriving (e.g. a preceding child and its parent were split across
     * record-stream files) we keep waiting instead of failing immediately on incomplete data. Once
     * enough items have been processed without any state change, re-throws so a genuine validation
     * mismatch is reported rather than silently swallowed.
     *
     * @return true if the assertion has now passed
     */
    private boolean tryValidateNow() {
        if (!allIdsHaveItems) {
            allIdsHaveItems = allIds.stream().allMatch(items::containsKey);
        }
        if (!allIdsHaveItems && withLogging) {
            // [block-assert-diag] When the set of not-yet-collected ids changes, log which expected
            // ids are still unseen and whether the spec registry can resolve them. Resolvable-but-unseen
            // points to a stream/translation gap (the matching record never arrives); unresolvable
            // points to a registry/wiring gap. This is the state in which the assertion times out.
            final var missing = allIds.stream()
                    .filter(id -> !items.containsKey(id))
                    .sorted()
                    .toList();
            if (!missing.equals(lastLoggedMissing)) {
                lastLoggedMissing = missing;
                final var resolvable = missing.stream()
                        .filter(id -> registry.getMaybeTxnId(id).isPresent())
                        .sorted()
                        .toList();
                log.info(
                        "[block-assert-diag] waiting on {} unseen id(s): {} (registry-resolvable of those: {}); collected: {}",
                        missing.size(),
                        missing,
                        resolvable,
                        items.keySet());
            }
        }
        if (allIdsHaveItems) {
            // Only re-run the validator when collected state has changed since the
            // last failed attempt — count both entries and skipped synth items,
            // since skipped synths affect firstExpectedUserNonce() used by validators.
            final var currentSnapshot = items.values().stream()
                    .mapToInt(v -> v.entries().size() + v.numSkippedSynthItems().get())
                    .sum();
            if (currentSnapshot == stateSnapshotAtLastValidation) {
                // No new items arrived since the last failed validation.  After
                // enough consecutive unchanged calls, conclude that no more items
                // are coming and surface the error as a genuine failure.
                if (lastValidationError != null && ++unchangedCallsSinceLastFailure > SETTLE_ITEM_THRESHOLD) {
                    throw lastValidationError;
                }
                return false;
            }
            unchangedCallsSinceLastFailure = 0;
            try {
                validator.assertValid(spec, items);
                lastValidationError = null;
                return true;
            } catch (final AssertionError | RuntimeException e) {
                final var error = (e instanceof AssertionError ae) ? ae : new AssertionError(e.getMessage(), e);
                stateSnapshotAtLastValidation = currentSnapshot;
                lastValidationError = error;
                if (withLogging) {
                    log.info("Validation not yet passing (items may still be arriving): {}", e.getMessage());
                }
                return false;
            }
        }
        return false;
    }

    /**
     * Attempts to match a stream item against the registered spec IDs.
     *
     * @return {@code true} if the item matched a registered ID (or can be discarded),
     *         {@code false} if no ID is registered yet for this item and it should be retried later
     */
    private boolean tryMatch(@NonNull final RecordStreamItem item) {
        final var observedId = item.getRecord().getTransactionID();
        boolean allResolvable = true;
        for (final var id : allIds) {
            final var maybeTxnId = registry.getMaybeTxnId(id);
            if (maybeTxnId.isEmpty()) {
                allResolvable = false;
                continue;
            }
            if (baseFieldsMatch(maybeTxnId.get(), observedId)) {
                // Guard against duplicate delivery (e.g. from retry in StreamFileAlterationListener)
                // using the raw protobuf timestamp to avoid the cost of RecordStreamEntry.from()
                final var consensusTime = timestampToInstant(item.getRecord().getConsensusTimestamp());
                if (!seenConsensusTimestamps.add(consensusTime)) {
                    return true; // already processed this item
                }
                final var entry = RecordStreamEntry.from(item);
                final var isSynthItem = isSynthItem(entry);
                if (skipSynthItems == SkipSynthItems.NO || !isSynthItem) {
                    if (withLogging) {
                        log.info("Saw {} as {}", id, observedId);
                    }
                    items.computeIfAbsent(id, ignore -> newVisibleItems())
                            .entries()
                            .add(entry);
                } else {
                    items.computeIfAbsent(id, ignore -> newVisibleItems()).trackSkippedSynthItem();
                }
                return true;
            }
        }
        // If all IDs are resolvable (registered) and none matched, the item is
        // genuinely unrelated — no need to buffer it for retry.
        return allResolvable;
    }

    private static boolean isSynthItem(@NonNull final RecordStreamEntry entry) {
        final var receipt = entry.transactionRecord().getReceipt();
        return entry.function() == NodeStakeUpdate
                || (receipt.getAccountID().hasAccountNum()
                        && receipt.getAccountID().getAccountNum() < FIRST_USER_NUM)
                || (receipt.hasFileID() && receipt.getFileID().getFileNum() < FIRST_USER_NUM);
    }
}
