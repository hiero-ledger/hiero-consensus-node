// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gossip.shadowgraph;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import org.hiero.base.crypto.Hash;

/**
 * Utility routines to generate formatted log string for sync-related variables.
 */
public final class SyncLogging {
    public static final int BRIEF_HASH_LENGTH = 4;

    /**
     * This type is not constructable
     */
    private SyncLogging() {}

    public static String toShortShadows(Collection<ShadowEvent> shadows) {
        if (shadows == null) {
            return "null";
        }
        return shadows.stream()
                .map(s -> s.getEvent().getDescriptor().toString())
                .collect(Collectors.joining(","));
    }

    public static String toShortHashes(List<Hash> hashes) {
        if (hashes == null) {
            return "null";
        }
        return hashes.stream().map(h -> h.toHex(BRIEF_HASH_LENGTH)).collect(Collectors.joining(","));
    }

    public static String toShortBooleans(List<Boolean> booleans) {
        if (booleans == null) {
            return "null";
        }
        return booleans.stream().map(b -> Boolean.TRUE.equals(b) ? "T" : "F").collect(Collectors.joining(","));
    }
}
