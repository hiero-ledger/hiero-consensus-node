// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.app.model.schema;

import com.hedera.pbj.runtime.FieldDefinition;
import com.hedera.pbj.runtime.FieldType;
import com.hedera.pbj.runtime.Schema;

/**
 * Schema for Account model object. Generate based on protobuf schema.
 */
public final class AccountSchema implements Schema {

    /**
     * Private constructor to prevent instantiation.
     */
    private AccountSchema() {
        // no-op
    }

    // -- FIELD DEFINITIONS ---------------------------------------------

    /**
     * <b>(1)</b>
     */
    public static final FieldDefinition ACCOUNT_ID =
            new FieldDefinition("accountId", FieldType.MESSAGE, false, false, false, 1);

    /**
     * <b>(2)</b>
     */
    public static final FieldDefinition NAME = new FieldDefinition("name", FieldType.STRING, false, false, false, 2);

    /**
     * <b>(3)</b>
     */
    public static final FieldDefinition BALANCE =
            new FieldDefinition("balance", FieldType.UINT64, false, false, false, 3);

    // -- OTHER METHODS -------------------------------------------------

    /**
     * Check if a field definition belongs to this schema.
     *
     * @param f field def to check
     * @return true if it belongs to this schema
     */
    public static boolean valid(final FieldDefinition f) {
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
            case 1 -> ACCOUNT_ID;
            case 2 -> NAME;
            case 3 -> BALANCE;
            default -> null;
        };
    }
}
