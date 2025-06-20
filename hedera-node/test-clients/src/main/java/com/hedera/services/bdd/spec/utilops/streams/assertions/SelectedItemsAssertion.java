// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.streams.assertions;

import com.hedera.node.app.hapi.utils.forensics.RecordStreamEntry;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.stream.proto.RecordStreamItem;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiPredicate;

/**
 * An assertion that validates a stream item against a predicate, and after finding an expected number of items,
 * validates them against a given {@link VisibleItemsValidator}.
 */
public class SelectedItemsAssertion implements RecordStreamAssertion {
    public static final String SELECTED_ITEMS_KEY = "SELECTED_ITEMS";

    private final int expectedCount;
    private final HapiSpec spec;
    private final BiPredicate<HapiSpec, RecordStreamItem> test;
    private final VisibleItemsValidator validator;
    private final List<RecordStreamEntry> selectedEntries = new ArrayList<>();

    public SelectedItemsAssertion(
            final int expectedCount,
            @NonNull final HapiSpec spec,
            @NonNull final BiPredicate<HapiSpec, RecordStreamItem> test,
            @NonNull final VisibleItemsValidator validator) {
        this.expectedCount = expectedCount;
        this.spec = spec;
        this.test = test;
        this.validator = validator;
    }

    @Override
    public boolean isApplicableTo(@NonNull final RecordStreamItem item) {
        return test.test(spec, item);
    }

    @Override
    public boolean test(@NonNull final RecordStreamItem item) throws AssertionError {
        final var entry = RecordStreamEntry.from(item);
        selectedEntries.add(entry);
        if (selectedEntries.size() == expectedCount) {
            validator.assertValid(
                    spec, Map.of(SELECTED_ITEMS_KEY, new VisibleItems(new AtomicInteger(), selectedEntries)));
            return true;
        }
        return false;
    }
}
