// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.legacy;

/**
 * An object that knows how to parse a swirlds log.
 */
public interface SwirldsLogParser<T> {

    /**
     * Parse a line from the log.
     *
     * @param line
     * 		the line to parse
     * @return a log entry if one was found. If the line is invalid then return null.
     */
    T parse(String line);
}
