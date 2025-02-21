// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.legacy.json;

import java.util.function.Predicate;

/**
 * A filter that checks if any exception is present.
 */
public class HasAnyExceptionFilter implements Predicate<JsonLogEntry> {

    public static HasAnyExceptionFilter hasAnyException() {
        return new HasAnyExceptionFilter();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean test(JsonLogEntry jsonLogEntry) {
        return jsonLogEntry.hasException();
    }
}
