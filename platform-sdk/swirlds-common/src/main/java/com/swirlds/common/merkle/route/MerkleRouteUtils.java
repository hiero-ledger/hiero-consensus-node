// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.route;

import static com.swirlds.base.formatting.StringFormattingUtils.formattedList;

/**
 * Utility methods for operating on merkle routes.
 */
public final class MerkleRouteUtils {

    private MerkleRouteUtils() {}

    /**
     * Convert a merkle route to a string representation that looks like a file system path.
     *
     * @param route
     * 		the merkle route
     * @return the string representation
     */
    public static String merkleRouteToPathFormat(final MerkleRoute route) {
        final StringBuilder sb = new StringBuilder("/");
        formattedList(sb, route.iterator(), "/");
        return sb.toString();
    }
}
