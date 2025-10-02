// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.validators.block;

import com.swirlds.common.merkle.utility.MerkleTreeVisualizer;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.regex.Pattern;

/**
 * A utility for extracting root mnemonic from a text visualization of a Merkle tree.
 */
public class RootHashUtils {
    private static final Pattern STATE_ROOT_PATTERN = Pattern.compile(".*VirtualMap.*/.*\\s+(.+)");

    /**
     * Extracts the root mnemonic from a text visualization of a Merkle tree created by {@link MerkleTreeVisualizer}.
     * @param visualizedHashes the text visualization
     * @return the root mnemonic
     */
    public static String extractRootMnemonic(@NonNull final String visualizedHashes) {
        String rootMnemonic = null;
        final var lines = visualizedHashes.split("\\n");

        for (final var line : lines) {
            final var stateRootMatcher = STATE_ROOT_PATTERN.matcher(line);
            if (stateRootMatcher.matches()) {
                rootMnemonic = stateRootMatcher.group(1);
            }
        }

        return rootMnemonic;
    }
}
