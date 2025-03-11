// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.domain;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Objects;

/**
 * A POJO for Jackson to use in storing a list of Base64-encoded {@code (TransactionBody, TransactionRecord)} pairs
 * along with a placeholder entity number used to fuzzy-match the entity ids in these pairs.
 */
public class RecordSnapshot {
    private long placeholderNum;
    private List<EncodedItem> encodedItems;

    public static RecordSnapshot from(final long placeholderNum, @NonNull final List<ParsedItem> postPlaceholderItems) {
        Objects.requireNonNull(postPlaceholderItems);
        final var snapshot = new RecordSnapshot();
        snapshot.setPlaceholderNum(placeholderNum);
        final var encodedItems = postPlaceholderItems.stream()
                .map(item -> EncodedItem.fromParsed(item.itemBody(), item.itemRecord()))
                .toList();
        snapshot.setEncodedItems(encodedItems);
        return snapshot;
    }

    public long getPlaceholderNum() {
        return placeholderNum;
    }

    public void setPlaceholderNum(final long placeholderNum) {
        this.placeholderNum = placeholderNum;
    }

    public List<EncodedItem> getEncodedItems() {
        return encodedItems;
    }

    public void setEncodedItems(@NonNull final List<EncodedItem> encodedItems) {
        this.encodedItems = Objects.requireNonNull(encodedItems);
    }
}
