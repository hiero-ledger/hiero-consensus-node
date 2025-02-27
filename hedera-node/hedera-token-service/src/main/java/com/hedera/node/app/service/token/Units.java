// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token;

/**
 * Utility class for units.
 */
public final class Units {
    private Units() {
        throw new UnsupportedOperationException("Utility Class");
    }

    /** Conversion factor from hbar to tinybar. */
    public static final long HBARS_TO_TINYBARS = 100_000_000L;
}
