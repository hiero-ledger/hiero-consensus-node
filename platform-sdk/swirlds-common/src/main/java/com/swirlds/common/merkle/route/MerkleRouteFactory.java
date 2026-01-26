// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.route;

import com.swirlds.common.merkle.route.internal.BinaryMerkleRoute;

/**
 * A factory for new merkle routes.
 */
public final class MerkleRouteFactory {

    private static MerkleRoute emptyRoute = new BinaryMerkleRoute();

    private MerkleRouteFactory() {}

    /**
     * Get an empty merkle route.
     */
    public static MerkleRoute getEmptyRoute() {
        return emptyRoute;
    }
}
