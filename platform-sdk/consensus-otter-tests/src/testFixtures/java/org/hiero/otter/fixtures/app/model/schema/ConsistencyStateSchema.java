// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.app.model.schema;

import com.hedera.pbj.runtime.FieldDefinition;
import com.hedera.pbj.runtime.FieldType;
import com.hedera.pbj.runtime.Schema;

/**
 * Schema for ConsistencyState model object. Generate based on protobuf schema.
 */
public final class ConsistencyStateSchema implements Schema {

    /**
     * Private constructor to prevent instantiation.
     */
    private ConsistencyStateSchema() {
        // no-op
    }

    // -- FIELD DEFINITIONS ---------------------------------------------

    /**
     * <b>(1)</b>
     */
    public static final FieldDefinition RUNNING_CHECKSUM =
            new FieldDefinition("running_checksum", FieldType.UINT64, false, false, false, 1);

    /**
     * <b>(2)</b>
     */
    public static final FieldDefinition ROUNDS_HANDLED =
            new FieldDefinition("rounds_handled", FieldType.UINT64, false, false, false, 2);

    // -- OTHER METHODS -------------------------------------------------

    /**
     * Check if a field definition belongs to this schema.
     *
     * @param f field def to check
     * @return true if it belongs to this schema
     */
    public static boolean valid(FieldDefinition f) {
        return f != null && getField(f.number()) == f;
    }

    /**
     * Get a field definition given a field number
     *
     * @param fieldNumber the fields number to get def for
     * @return field def or null if field number does not exist
     */
    public static FieldDefinition getField(final int fieldNumber) {
        return switch (fieldNumber) {
            case 1 -> RUNNING_CHECKSUM;
            case 2 -> ROUNDS_HANDLED;
            default -> null;
        };
    }
}
