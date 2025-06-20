// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.streams.assertions;

import com.hedera.node.app.hapi.utils.forensics.RecordStreamEntry;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Represents a list of {@link RecordStreamEntry} items that are visible to a {@link RecordStreamAssertion},
 * along with a count of synthetic items that were skipped in the process of identifying them.
 * @param numSkippedSynthItems The number of synthetic items that were skipped.
 * @param entries The visible items.
 */
public record VisibleItems(@NonNull AtomicInteger numSkippedSynthItems, @NonNull List<RecordStreamEntry> entries) {
    /**
     * Constructs a new {@link VisibleItems} instance with a count of skipped synthetic items set to 0 and an
     * empty list of visible items.
     * @return The new {@link VisibleItems} instance.
     */
    public static VisibleItems newVisibleItems() {
        return new VisibleItems(new AtomicInteger(0), new ArrayList<>());
    }

    /**
     * Marks another skipped synthetic item as having been skipped.
     */
    public void trackSkippedSynthItem() {
        numSkippedSynthItems.incrementAndGet();
    }

    /**
     * Returns the number of visible items.
     * @return The number of visible items.
     */
    public int size() {
        return entries.size();
    }

    /**
     * Gets the first visible item.
     * @return The first visible item.
     */
    public RecordStreamEntry getFirst() {
        return entries.getFirst();
    }

    /**
     * Gets the visible item at the specified index.
     * @param index The index of the visible item to get.
     * @return The visible item at the specified index.
     */
    public RecordStreamEntry get(final int index) {
        return entries.get(index);
    }

    /**
     * Returns the first expected user nonce.
     * @return The first expected user nonce.
     */
    public int firstExpectedUserNonce() {
        return 1 + numSkippedSynthItems.get();
    }

    /**
     * Returns the final statuses of the visible items.
     * @return The final statuses of the visible items.
     */
    public ResponseCodeEnum[] statuses() {
        return entries.stream().map(RecordStreamEntry::finalStatus).toArray(ResponseCodeEnum[]::new);
    }

    /**
     * Returns the functions of the visible items.
     * @return The functions of the visible items.
     */
    public HederaFunctionality[] functions() {
        return entries.stream().map(RecordStreamEntry::function).toArray(HederaFunctionality[]::new);
    }
}
