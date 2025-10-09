// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.core;

import edu.umd.cs.findbugs.annotations.NonNull;

public final class LabelNamesAndValues extends LabelValues {

    private final String[] namesAndValues;

    public LabelNamesAndValues(String... namesAndValues) {
        this.namesAndValues = namesAndValues;
    }

    @Override
    public int size() {
        return namesAndValues.length / 2;
    }

    @NonNull
    @Override
    public String get(int index) {
        return namesAndValues[2 * index + 1];
    }
}
