// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.streams.assertions;

import com.hedera.services.bdd.spec.HapiSpec;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;

/**
 * A validator for visible items, called once all items have been collected.
 */
public interface VisibleItemsValidator {
    /**
     * A no-op validator to clarify intent when constructing a {@link SelectedItemsAssertion} that should pass whenever
     * its managing {@link EventualRecordStreamAssertion} discovers the expected number of selected items within the
     * timeout.
     */
    VisibleItemsValidator EXISTENCE_ONLY_VALIDATOR = (spec, allItems) -> {};

    /**
     * Asserts that the visible items are valid.
     *
     * @param spec the current spec
     * @param allItems all visible items
     * @throws AssertionError if the items are invalid
     */
    void assertValid(@NonNull HapiSpec spec, @NonNull Map<String, VisibleItems> allItems) throws AssertionError;
}
