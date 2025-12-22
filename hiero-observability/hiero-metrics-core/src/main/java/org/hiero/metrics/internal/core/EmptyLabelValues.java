// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.core;

import edu.umd.cs.findbugs.annotations.NonNull;

public final class EmptyLabelValues extends LabelValues {

    static final EmptyLabelValues INSTANCE = new EmptyLabelValues();

    private EmptyLabelValues() {}

    @Override
    public int size() {
        return 0;
    }

    @NonNull
    @Override
    public String get(int index) {
        throw new IndexOutOfBoundsException("Label values is empty");
    }
}
