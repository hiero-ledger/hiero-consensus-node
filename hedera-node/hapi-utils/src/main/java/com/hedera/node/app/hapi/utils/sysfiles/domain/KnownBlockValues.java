// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.sysfiles.domain;

import com.google.common.primitives.Longs;
import com.hedera.node.app.hapi.utils.sysfiles.ParsingUtils;
import java.util.Arrays;
import org.hiero.base.utility.CommonUtils;

public record KnownBlockValues(byte[] hash, long number) {
    private static final int KECCAK256_HASH_LENGTH = 32;

    public static final KnownBlockValues MISSING_BLOCK_VALUES = new KnownBlockValues(new byte[0], 0);

    public static KnownBlockValues from(final String literal) {
        if (literal.isBlank()) {
            return MISSING_BLOCK_VALUES;
        }
        return ParsingUtils.fromTwoPartDelimited(
                literal,
                "@",
                (hash, number) -> {
                    if (hash.length != KECCAK256_HASH_LENGTH) {
                        throw new IllegalArgumentException("Wrong hash length in '" + literal + "'");
                    }
                    if (number <= 0) {
                        throw new IllegalArgumentException("Non-positive block number in '" + literal + "'");
                    }
                },
                CommonUtils::unhex,
                Long::parseLong,
                KnownBlockValues::new);
    }

    public boolean isMissing() {
        return hash.length == 0;
    }

    @Override
    public boolean equals(final Object other) {
        if (this == other) {
            return true;
        }
        if (other == null || KnownBlockValues.class != other.getClass()) {
            return false;
        }
        final var that = (KnownBlockValues) other;
        return this.number == that.number && Arrays.equals(this.hash, that.hash);
    }

    @Override
    public int hashCode() {
        return 31 * Longs.hashCode(number) + Arrays.hashCode(hash);
    }

    @Override
    public String toString() {
        return "KnownBlockValues{" + "hash=" + CommonUtils.hex(hash) + ", number=" + number + '}';
    }
}
