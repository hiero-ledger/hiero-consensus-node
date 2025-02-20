// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.scratchpad;

/**
 * A scratchpad type for testing.
 */
public enum TestScratchpadType implements ScratchpadType {
    FOO(0),
    BAR(1),
    BAZ(2);

    private final int fieldId;

    TestScratchpadType(final int fieldId) {
        this.fieldId = fieldId;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getFieldId() {
        return fieldId;
    }
}
