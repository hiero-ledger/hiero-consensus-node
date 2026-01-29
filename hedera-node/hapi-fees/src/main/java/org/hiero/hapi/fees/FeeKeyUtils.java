// SPDX-License-Identifier: Apache-2.0
package org.hiero.hapi.fees;

import com.hedera.hapi.node.base.Key;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Utility class for key-related fee calculations per HIP-1261.
 */
public final class FeeKeyUtils {

    private FeeKeyUtils() {
        // Utility class
    }

    /**
     * Utility: Counts all keys including nested ones in threshold/key lists.
     * Useful for calculating KEYS extra fees per HIP-1261.
     *
     * @param key The key structure to count
     * @return The total number of simple keys (ED25519, ECDSA_SECP256K1, ECDSA_384)
     */
    public static long countKeys(@NonNull final Key key) {
        return switch (key.key().kind()) {
            case ED25519, ECDSA_SECP256K1, ECDSA_384 -> 1L;
            case THRESHOLD_KEY ->
                key.thresholdKeyOrThrow().keys().keys().stream()
                        .mapToLong(FeeKeyUtils::countKeys)
                        .sum();
            case KEY_LIST ->
                key.keyListOrThrow().keys().stream()
                        .mapToLong(FeeKeyUtils::countKeys)
                        .sum();
            default -> 0L;
        };
    }
}
