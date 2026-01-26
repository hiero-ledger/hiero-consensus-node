// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.creator.impl.util;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Utility methods for working with collections.
 */
public final class CollectionsUtilities {

    private CollectionsUtilities() {
        // Utility class
    }

    /**
     * Generates all permutations of the given collection.
     *
     * @param collection the collection to permute
     * @param <T> the type of elements in the collection
     * @return a list containing all permutations, where each permutation is a list
     */
    @NonNull
    public static <T> List<List<T>> permutations(@NonNull final Collection<T> collection) {
        final List<T> list = new ArrayList<>(collection);
        final List<List<T>> result = new ArrayList<>();
        permute(list, 0, result);
        return result;
    }

    private static <T> void permute(@NonNull final List<T> list, final int start, @NonNull final List<List<T>> result) {
        if (start == list.size()) {
            result.add(new ArrayList<>(list));
            return;
        }
        for (int i = start; i < list.size(); i++) {
            Collections.swap(list, start, i);
            permute(list, start + 1, result);
            Collections.swap(list, start, i); // backtrack
        }
    }
}
