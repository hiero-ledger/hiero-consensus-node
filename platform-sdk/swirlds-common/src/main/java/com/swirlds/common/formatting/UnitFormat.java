// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.formatting;

/**
 * Possible formats for units.
 */
public enum UnitFormat {
    /**
     * The original unit is used
     */
    UNSIMPLIFIED,
    /**
     * The original unit is simplified to a single unit of appropriate size.
     */
    SINGLE_SIMPLIFIED,
    /**
     * The granularity of the original unit is maintained, but the output is split into multiple units.
     */
    MULTI_SIMPLIFIED
}
