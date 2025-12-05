// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1261.utils;

import java.util.EnumMap;
import org.hiero.hapi.support.fees.Extra;

/**
 * This class is used as a builder for simple fees parameters.
 */
public class SimpleFeesParams {
    private final EnumMap<Extra, Long> counts = new EnumMap<>(Extra.class);

    /** Create an empty params builder. */
    public static SimpleFeesParams create() {
        return new SimpleFeesParams();
    }

    /** Put an Extra count. */
    public SimpleFeesParams put(final Extra extra, final long count) {
        counts.put(extra, count);
        return this;
    }

    /** Number of signatures. */
    public SimpleFeesParams signatures(final long count) {
        return put(Extra.SIGNATURES, count);
    }

    /** Number of bytes. */
    public SimpleFeesParams bytes(final long count) {
        return put(Extra.BYTES, count);
    }

    /** Number of keys. */
    public SimpleFeesParams keys(final long count) {
        return put(Extra.KEYS, count);
    }

    /** Number of accounts. */
    public SimpleFeesParams accounts(final long count) {
        return put(Extra.ACCOUNTS, count);
    }

    /** Get the built params as a map. */
    public EnumMap<Extra, Long> get() {
        return counts;
    }
}
